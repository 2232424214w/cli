package com.bettercli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bettercli.llm.LlmClient;
import com.bettercli.memory.MemoryManager;
import com.bettercli.runtime.CancellationContext;
import com.bettercli.tool.ToolRegistry;
import com.bettercli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Function;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（最多 Worker 池大小并发，默认 2）
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link java.util.concurrent.BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;
    /**
     * 单次 run 内允许的动态重规划次数上限（防 replan 风暴）。
     * 触发条件克制：仅在 step 执行失败（FAILED）或重试耗尽仍未通过审查（EXHAUSTED）时回调 planner。
     */
    private static final int MAX_REPLAN_PER_RUN = 2;

    private final LlmClient llmClient;
    private SubAgent planner;
    private List<SubAgent> workers;
    private SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private Supplier<String> externalContextSupplier = () -> "";
    private Function<AgentRole, LlmClient> roleClientResolver;
    private com.bettercli.skill.SkillRegistry skillRegistry;
    private com.bettercli.skill.SkillContextBuffer skillContextBuffer;
    private List<String> workerSpecialties;
    // Multi-Agent 共享黑板（对标 2026 Blackboard 架构）。每次 run() 重建；
    // worker/reviewer 产物双写进黑板，routing 决策入审计。供测试断言与后续 p2p/workflow 阶段复用。
    private SharedState sharedState;

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, String assignee,
                                  String result, StepStatus status) {
        static ExecutionStep pending(String id, String description, String type,
                                     List<String> dependencies, String assignee) {
            return new ExecutionStep(id, description, type, dependencies, assignee, null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, assignee, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, assignee, result, StepStatus.FAILED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, assignee, result, StepStatus.RUNNING);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    /**
     * 单步执行结局，供主循环判断是否触发动态重规划。
     * <ul>
     *   <li>{@link #COMPLETED}  - 审查通过（含重试后通过），或审查 LLM 调用失败时保留结果（现有兜底行为）</li>
     *   <li>{@link #FAILED}     - Worker 执行 LLM 出错 / 结果为空 / 用户取消（前置失败类，下游依赖会被卡）</li>
     *   <li>{@link #EXHAUSTED}  - 重试耗尽 {@link #MAX_RETRIES_PER_STEP} 次仍未通过审查（审查连续拒绝类）</li>
     * </ul>
     * 后两类是动态重规划的触发条件。
     */
    enum StepOutcome {
        COMPLETED, FAILED, EXHAUSTED;

        boolean shouldTriggerReplan() {
            return this == FAILED || this == EXHAUSTED;
        }
    }

    /**
     * {@link #runStep} 的返回值：结局 + 失败原因（供 replan prompt 使用）。
     */
    record StepRunResult(StepOutcome outcome, String failureReason) {
        static StepRunResult completed() {
            return new StepRunResult(StepOutcome.COMPLETED, null);
        }

        static StepRunResult of(StepOutcome outcome, String failureReason) {
            return new StepRunResult(outcome, failureReason);
        }
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        // 默认所有角色共用主模型；可通过 setRoleClientResolver 注入角色级模型分配
        this.roleClientResolver = role -> llmClient;
        this.memoryManager = memoryManager;
        rebuildSubAgents();
    }

    /**
     * 注入角色级模型分配器（{@link RoleModelResolver}），让 Planner / Reviewer / Worker
     * 用不同模型。必须在 {@link #run(String)} 之前调用——会重建所有 SubAgent 并重新下发
     * 已设置的外部上下文 / Skill 系统。
     */
    public void setRoleClientResolver(Function<AgentRole, LlmClient> resolver) {
        this.roleClientResolver = resolver == null ? role -> llmClient : resolver;
        rebuildSubAgents();
    }

    /**
     * 注入 Worker 专长列表（按 worker 顺序对应）。让多个 Worker 有差异化专长，并把
     * 团队名单注入 Planner 的 prompt，使规划者能在每个 step 的 assignee 指定最合适的 Worker。
     * 必须在 {@link #run(String)} 之前调用。null/空 则 Worker 无专长、Planner 不强调指派。
     */
    public void setWorkerSpecialties(List<String> specialties) {
        this.workerSpecialties = specialties;
        rebuildSubAgents();
    }

    /**
     * 重建四个 SubAgent（planner / 2 worker / reviewer），按当前 roleClientResolver 取模型，
     * 并重新下发已设置的外部上下文与 Skill 系统，保证 setter 重建后配置不丢。
     */
    private void rebuildSubAgents() {
        this.planner = new SubAgent("planner", AgentRole.PLANNER,
                roleClientResolver.apply(AgentRole.PLANNER), toolRegistry);
        this.workers = buildWorkers();
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER,
                roleClientResolver.apply(AgentRole.REVIEWER), toolRegistry);
        // 重新下发已设置的外部上下文
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
        // 把团队 Worker 名单 + 专长注入 Planner，让规划者能按专长指派 assignee
        planner.setTeamWorkersContext(buildTeamWorkersContext());
        // 重新下发已设置的 Skill 系统
        applySkillSystem();
    }

    private List<SubAgent> buildWorkers() {
        List<SubAgent> result = new ArrayList<>();
        int count = 2;
        for (int i = 0; i < count; i++) {
            String name = "worker-" + (i + 1);
            String specialty = (workerSpecialties != null && i < workerSpecialties.size())
                    ? workerSpecialties.get(i) : null;
            result.add(new SubAgent(name, AgentRole.WORKER,
                    roleClientResolver.apply(AgentRole.WORKER), toolRegistry, specialty));
        }
        return List.copyOf(result);
    }

    /**
     * 构造注入 Planner prompt 的团队名单文本。即使没配专长，也列出 Worker 名字，
     * 让规划者可以按步骤性质指派 assignee。
     */
    private String buildTeamWorkersContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("可用执行者（Worker）名单——请在每个 step 的 assignee 字段选择最匹配专长的一个：\n");
        for (SubAgent worker : workers) {
            sb.append("- ").append(worker.getName());
            String specialty = worker.getSpecialty();
            if (specialty != null && !specialty.isBlank()) {
                sb.append("：").append(specialty.trim());
            } else {
                sb.append("：通用执行");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 返回某角色当前实际使用的模型标签（provider/model），供 /team 状态展示与 ablation 记录。
     */
    public String roleModelLabel(AgentRole role) {
        LlmClient client = roleClientResolver.apply(role);
        if (client == null) {
            return "(no model)";
        }
        return client.getProviderName() + "/" + client.getModelName();
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。Multi-Agent 三个角色共享同一 SkillRegistry（索引一致），
     * 但共享同一 SkillContextBuffer——简化实现，避免角色级 buffer 隔离的工程开销。
     * 任务书 §3.6 描述的"角色独立 buffer"作为可观察的优化项暂未启用。
     */
    public void setSkillSystem(com.bettercli.skill.SkillRegistry skillRegistry,
                               com.bettercli.skill.SkillContextBuffer skillContextBuffer) {
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        applySkillSystem();
    }

    private void applySkillSystem() {
        if (skillRegistry == null && skillContextBuffer == null) {
            return;
        }
        planner.setSkillRegistry(skillRegistry);
        planner.setSkillContextBuffer(skillContextBuffer);
        for (SubAgent worker : workers) {
            worker.setSkillRegistry(skillRegistry);
            worker.setSkillContextBuffer(skillContextBuffer);
        }
        reviewer.setSkillRegistry(skillRegistry);
        reviewer.setSkillContextBuffer(skillContextBuffer);
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        // 每次运行重建共享黑板（对标 2026 Blackboard：显式共享状态 + routing 审计）
        this.sharedState = new SharedState();
        this.sharedState.setGoal(userInput, null);
        memoryManager.addUserMessage(userInput);
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentMessage planResult = planner.execute(planMessage, out);
        try {
            // 不在此处 clearHistory：保留 planner 对话历史，供执行阶段动态重规划（replan）复用上下文。
            // 本 try 的 finally 统一清理，避免跨 run 污染。
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前多 Agent 任务。";
            }

            if (planResult.type() == AgentMessage.Type.ERROR) {
                return "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
            }
            if (planResult.content() == null || planResult.content().isBlank()) {
                return "❌ 规划失败：规划者未能生成有效计划";
            }
            // planner 产物写入黑板（所有权：PLANNER）
            this.sharedState.setPlan(planResult.content(), AgentRole.PLANNER);

            // 2. 解析计划
            List<ExecutionStep> steps = parsePlan(planResult.content());
            if (steps.isEmpty()) {
                return "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
            }

            out.println(AnsiStyle.heading("📋 执行计划"));
            out.println(summarizeSteps(steps) + "\n");

            // 3. 执行阶段：按依赖顺序分配给执行者
            out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
            Map<String, Integer> retryCount = new ConcurrentHashMap<>();
            int singleStepCursor = 0;
            int batchIndex = 0;
            int replanCount = 0;

            while (true) {
                if (CancellationContext.isCancelled()) {
                    return "⏹️ 已取消当前多 Agent 任务。";
                }
                List<ExecutionStep> executable = getExecutableSteps(steps);
                if (executable.isEmpty()) {
                    break;
                }
                batchIndex++;

                StepRunResult replanTrigger = null;   // 本批次首个可触发 replan 的结局
                String replanAnchorStepId = null;    // 对应的失败 step id（replan 锚点）

                if (executable.size() == 1) {
                    // 单步批次：直接串行流式输出，保持实时打字观感
                    ExecutionStep step = executable.get(0);
                    SubAgent worker = pickWorker(step, singleStepCursor);
                    if (step.assignee() != null && worker != null && worker.getName().equals(step.assignee())) {
                        out.println("🎯 步骤 [" + step.id() + "] 由 " + step.assignee() + " 执行（规划者指派）");
                    }
                    singleStepCursor++;
                    String context = buildStepContext(steps, step);
                    StepRunResult rr = runStep(step, steps, retryCount, worker, reviewer, context, out);
                    // 持久记忆：不再每步 clearHistory，让 Worker 记得自己干过什么；
                    // 上下文超 window 时由 SubAgent.maybeCompactHistory 自动压缩早期消息。
                    if (rr.outcome().shouldTriggerReplan()) {
                        replanTrigger = rr;
                        replanAnchorStepId = step.id();
                    }
                } else {
                    // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按 step_id 顺序 flush
                    out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                            + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
                    Map<String, StepRunResult> outcomes = runBatchParallel(executable, steps, retryCount);
                    // 并行批次：取首个（按 step_id 顺序）可触发 replan 的结局作为重规划锚点
                    for (ExecutionStep step : executable) {
                        StepRunResult rr = outcomes.get(step.id());
                        if (rr != null && rr.outcome().shouldTriggerReplan()) {
                            replanTrigger = rr;
                            replanAnchorStepId = step.id();
                            break;
                        }
                    }
                }

                // 动态重规划：当某 step 执行失败（FAILED）或重试耗尽仍未通过审查（EXHAUSTED），
                // 且回调次数未超 MAX_REPLAN_PER_RUN 时，让 planner 基于已完成步骤 + 失败原因
                // 重新规划剩余步骤，替换当前 PENDING/FAILED 步骤，重新进入主循环。
                if (replanTrigger != null && replanCount < MAX_REPLAN_PER_RUN) {
                    replanCount++;
                    List<ExecutionStep> replanned = triggerReplan(steps, replanAnchorStepId,
                            replanTrigger.failureReason(), replanCount);
                    if (replanned != null) {
                        steps = replanned;
                    }
                    // replan 后继续主循环，重新计算可执行步骤
                }
            }

            // 5. 处理因前置失败而无法执行的残留步骤（显式提示用户）
            for (ExecutionStep step : steps) {
                if (step.status() == StepStatus.PENDING) {
                    out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
                }
            }

            // 6. 汇总结果
            String finalResult = buildFinalResult(steps);
            memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);

            return finalResult;
        } finally {
            // 统一清理 planner 对话历史：执行阶段可能因 replan 多次复用 planner 上下文，
            // run 结束后清空（保留系统提示词），避免下次 run 带入上次规划历史。
            planner.clearHistory();
        }
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode root = mapper.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                // 尝试 "tasks" 字段（兼容 Plan-and-Execute 的格式）
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 第一遍：创建步骤（重编号 + 读取 assignee 指派）
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                String assignee = stepNode.path("assignee").asText(null);
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>(),
                        normalizeAssignee(assignee)));
            }

            // 第二遍：建立依赖
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex++;
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        String mapped = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(mapped);
                    }
                    // 替换步骤的依赖（保留 assignee）
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.assignee(), old.result(), old.status()));
                    }
                }
            }

            return steps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return List.of();
        }
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    /**
     * 串行单步路由：若规划者指定了 assignee 且该 worker 存在，就用它；否则按游标轮询。
     * 路由决策写入共享黑板 routingLog（对标 2026 routing 审计）。
     */
    private SubAgent pickWorker(ExecutionStep step, int singleStepCursor) {
        SubAgent named = findWorker(step.assignee());
        SubAgent picked;
        String reason;
        if (named != null) {
            picked = named;
            reason = "规划者指派 " + step.assignee();
        } else {
            picked = workers.get(singleStepCursor % workers.size());
            reason = step.assignee() != null
                    ? "规划者指派的 " + step.assignee() + " 不存在，回退轮询"
                    : "未指派，按游标轮询";
        }
        if (sharedState != null) {
            sharedState.recordRouting(step.id(), picked.getName(), reason);
        }
        return picked;
    }

    /**
     * 并行批次路由：尽量取 assignee 指定的 worker；若已被同批次其他步骤占用或不存在，回退到任意空闲 worker。
     */
    private SubAgent takeWorker(BlockingQueue<SubAgent> pool, String assignee) throws InterruptedException {
        if (assignee == null || assignee.isBlank()) {
            return pool.take();
        }
        synchronized (pool) {
            List<SubAgent> drained = new ArrayList<>();
            SubAgent found = null;
            SubAgent w;
            while ((w = pool.poll()) != null) {
                if (found == null && assignee.equals(w.getName())) {
                    found = w;
                } else {
                    drained.add(w);
                }
            }
            for (SubAgent d : drained) {
                pool.offer(d);
            }
            if (found != null) {
                return found;
            }
        }
        // 指派的 worker 当前被占用，回退到任意空闲 worker
        return pool.take();
    }

    private SubAgent findWorker(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (SubAgent worker : workers) {
            if (name.equals(worker.getName())) {
                return worker;
            }
        }
        return null;
    }

    /**
     * 规范化 assignee：仅当与某个已注册 worker 名字匹配时才保留，否则置 null（回退默认调度），
     * 避免规划者幻觉出不存在的 worker 名导致步骤无法路由。
     */
    private String normalizeAssignee(String assignee) {
        if (assignee == null || assignee.isBlank()) {
            return null;
        }
        String trimmed = assignee.trim();
        return findWorker(trimmed) != null ? trimmed : null;
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            return approvedNode.asBoolean(false);
        } catch (Exception e) {
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    /**
     * 解析检查者反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(issue.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(suggestion.asText()).append("\n");
                }
                return sb.toString().trim();
            }

            // 返回 summary 作为备选
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 获取当前 run 的共享黑板（Blackboard）。run() 之前或之后返回的实例可能为 null 或已结束态；
     * 主要供测试断言 routing 决策与 artifacts，以及后续 p2p/workflow 阶段复用。
     */
    public SharedState getSharedState() {
        return sharedState;
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    /**
     * 并行执行一批相互独立的步骤。
     *
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 流式输出写入步骤本地的 ByteArrayOutputStream；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     *
     * @return 各 step id 到其 {@link StepRunResult} 的映射，供主循环判断是否触发动态重规划。
     */
    private Map<String, StepRunResult> runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                                          Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "bettercli-multi-agent");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        Map<String, StepRunResult> outcomes = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                SubAgent worker = null;
                SubAgent localReviewer = new SubAgent(
                        "reviewer-" + step.id(), AgentRole.REVIEWER,
                        roleClientResolver.apply(AgentRole.REVIEWER), toolRegistry);
                try {
                    worker = takeWorker(workerPool, step.assignee());
                    if (step.assignee() != null && worker != null && worker.getName().equals(step.assignee())) {
                        stepOut.println("🎯 步骤 [" + step.id() + "] 由 " + step.assignee() + " 执行（规划者指派）\n");
                    }
                    if (sharedState != null && worker != null) {
                        sharedState.recordRouting(step.id(), worker.getName(), "并行批次派活");
                    }
                    StepRunResult rr = runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
                    outcomes.put(step.id(), rr);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                    outcomes.put(step.id(), StepRunResult.of(StepOutcome.FAILED,
                            "步骤 [" + step.id() + "] 并行执行被中断"));
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                    outcomes.put(step.id(), StepRunResult.of(StepOutcome.FAILED,
                            "步骤 [" + step.id() + "] 并行执行异常: " + e.getMessage()));
                } finally {
                    if (worker != null) {
                        // 持久记忆：不 clearHistory，Worker 跨步骤保留对话历史
                        workerPool.offer(worker);
                    }
                    stepOut.flush();
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
        executor.shutdownNow();

        // 按 step_id 顺序 flush 各步骤的缓冲输出，保证用户看到的执行过程有稳定顺序
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        return outcomes;
    }

    /**
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     *
     * @return {@link StepRunResult}：结局 + 失败原因。FAILED（Worker 出错/空/取消）与
     *         EXHAUSTED（重试耗尽未通过审查）两类结局会触发主循环的动态重规划；COMPLETED 不触发。
     */
    private StepRunResult runStep(ExecutionStep step, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount,
                                  SubAgent worker, SubAgent reviewer, String context,
                                  PrintStream out) {
        // 注入共享黑板 + 当前 worker 名，使该 worker 的 ask_peer 工具可用（阶段D p2p）
        toolRegistry.setSharedState(this.sharedState);
        toolRegistry.setCurrentWorkerName(worker.getName());
        // 把发给该 worker 的 peer 留言注入 context（对标 agent teams 共享消息）
        String inboxBlock = buildInboxBlock(worker.getName());
        String fullContext = inboxBlock.isEmpty() ? context : context + "\n" + inboxBlock;
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            // 取消不触发 replan：主循环顶部会捕获取消状态并退出整个 run
            return StepRunResult.completed();
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(taskMsg, fullContext, out);
        SubAgentResult workerEnvelope = worker.lastRunResult();
        if (workerEnvelope != null) {
            out.println(workerEnvelope.oneLineSummary());
        }
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            // 取消不触发 replan：主循环顶部会捕获取消状态并退出整个 run
            return StepRunResult.completed();
        }

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return StepRunResult.of(StepOutcome.FAILED,
                    "步骤 [" + step.id() + "] Worker 执行失败：" + result.content());
        }
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return StepRunResult.of(StepOutcome.FAILED,
                    "步骤 [" + step.id() + "] Worker 执行结果为空");
        }
        // worker 产物写入共享黑板（所有权：WORKER）。双写：step.result() 仍保留供旧路径，
        // 黑板成为后续 p2p/workflow 阶段的权威共享源。
        if (sharedState != null) {
            sharedState.putArtifact(step.id(), result.content(), AgentRole.WORKER);
        }

        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        if (workerEnvelope != null && workerEnvelope.needsAdversarialVerification()) {
            out.println("   ⚠️ 低置信度或涉及文件改动，审查者将实际核实 artifacts（对抗式验证）");
        }
        AgentMessage reviewResult = reviewer.reviewWithRubric(step.description(),
                workerEnvelope != null ? workerEnvelope : emptyEnvelope(result.content()), out);
        reviewer.clearHistory();
        // reviewer 反馈写入共享黑板（所有权：REVIEWER），供审计与后续 p2p 阶段复用。
        if (sharedState != null && reviewResult.content() != null) {
            sharedState.putReview(step.id(), reviewResult.content(), AgentRole.REVIEWER);
        }

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("⚠️ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，保留当前执行结果\n");
            updateStep(steps, step.id(), step.withResult(result.content()));
            // 审查基础设施失败（非 Worker 责任）：保留结果，不触发 replan
            return StepRunResult.completed();
        }

        boolean approved = parseReviewApproval(reviewResult.content());
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return StepRunResult.completed();
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = parseReviewIssues(reviewResult.content());
        String previousIssues = null;
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            // 收敛：本轮 issues 与上轮实质相同，或审查显式 converged → 停止辩论，保留当前结果
            if (ReflectionService.isDebateConverged(reviewResult.content(), previousIssues)) {
                out.println("🤝 步骤 [" + step.id() + "] 辩论已收敛，保留当前结果\n");
                updateStep(steps, step.id(), step.withResult(acceptedResult));
                return StepRunResult.completed();
            }
            previousIssues = issues;

            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，增量辩论第 " + retries + " 轮...");
            out.println("   反馈: " + issues + "\n");

            // 增量辩论：只改审查指出的点，不从头重做（对标阶段 2 反思循环收敛）
            String feedbackContext = ReflectionService.buildIncrementalDebateContext(
                    fullContext, acceptedResult, issues, retries);
            AgentMessage retryResult = worker.executeWithContext(taskMsg, feedbackContext, out);
            SubAgentResult retryEnvelope = worker.lastRunResult();
            if (retryEnvelope != null) {
                out.println(retryEnvelope.oneLineSummary());
            }
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            // 重试产物覆盖黑板 artifact（最新一次有效执行）
            if (sharedState != null) {
                sharedState.putArtifact(step.id(), acceptedResult, AgentRole.WORKER);
            }
            AgentMessage retryReview = reviewer.reviewWithRubric(step.description(),
                    retryEnvelope != null ? retryEnvelope : emptyEnvelope(acceptedResult), out);
            reviewer.clearHistory();
            // 重试 review 覆盖黑板（最新一次审查反馈）
            if (sharedState != null && retryReview.content() != null) {
                sharedState.putReview(step.id(), retryReview.content(), AgentRole.REVIEWER);
            }

            if (retryReview.type() == AgentMessage.Type.ERROR) {
                log.warn("Reviewer failed for step {} retry {}: {}", step.id(), retries, retryReview.content());
                approved = true;
                issues = "";
                break;
            }

            reviewResult = retryReview;
            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        // 循环结束后再判一次收敛（最后一轮审查可能刚给出相同 issues）
        if (!approved && ReflectionService.isDebateConverged(reviewResult.content(), previousIssues)) {
            out.println("🤝 步骤 [" + step.id() + "] 辩论已收敛，保留当前结果\n");
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            return StepRunResult.completed();
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
            return StepRunResult.completed();
        } else {
            String reason = "步骤 [" + step.id() + "] 经 " + MAX_RETRIES_PER_STEP
                    + " 次增量辩论仍未通过审查：" + issues;
            out.println("⚠️ " + reason + "，保留当前结果\n");
            return StepRunResult.of(StepOutcome.EXHAUSTED, reason);
        }
    }

    /**
     * 动态重规划：当某 step 执行失败（FAILED）或重试耗尽仍未通过审查（EXHAUSTED）时，
     * 让 planner 基于已完成步骤 + 失败原因重新规划剩余步骤。
     *
     * <p>合并策略：
     * <ul>
     *   <li>保留所有 {@link StepStatus#COMPLETED} 步骤（已完成，不重复执行）</li>
     *   <li>丢弃所有 {@link StepStatus#FAILED} / {@link StepStatus#PENDING} 步骤（失败或被卡住的剩余步骤）</li>
     *   <li>追加新计划步骤，id 加 {@code r<replanCount>_} 前缀避免与原 id 冲突；
     *       新计划内部依赖同步重映射，保证 {@link #getExecutableSteps} 能正确解析</li>
     * </ul>
     *
     * <p>新计划不显式依赖原已完成步骤的 id（planner 不知道原 id），但 replan prompt 已告知 planner
     * 哪些步骤完成及其结果摘要，由 planner 在新计划中自行引用必要信息。
     *
     * @param steps          当前步骤列表（含已完成 / 失败 / 待执行）
     * @param failedStepId   触发 replan 的失败步骤 id（写入 prompt 供 planner 定位问题）
     * @param failureReason  失败原因（FAILED / EXHAUSTED 的具体描述）
     * @param replanCount    本次 replan 的序号（用于 id 前缀，1-based）
     * @return 合并后的新步骤列表；若 replan LLM 调用失败或输出无法解析，返回 {@code null}（主循环保留原计划继续）
     */
    private List<ExecutionStep> triggerReplan(List<ExecutionStep> steps, String failedStepId,
                                              String failureReason, int replanCount) {
        out.println(AnsiStyle.heading("🔄 动态重规划（第 " + replanCount + "/" + MAX_REPLAN_PER_RUN + " 次）"));
        out.println("   触发原因：" + failureReason + "\n");

        StringBuilder replanPrompt = new StringBuilder();
        replanPrompt.append("之前的执行计划在执行中遇到问题，需要重新规划剩余步骤。\n\n");
        replanPrompt.append("原始任务：").append(sharedState.getGoal()).append("\n\n");
        replanPrompt.append("失败原因：").append(failureReason).append("\n");
        replanPrompt.append("失败的步骤：[").append(failedStepId).append("]\n\n");
        replanPrompt.append("已成功完成的步骤（不要重复执行，可在新计划中引用其结果）：\n");
        boolean anyCompleted = false;
        for (ExecutionStep s : steps) {
            if (s.status() == StepStatus.COMPLETED) {
                anyCompleted = true;
                replanPrompt.append("- [").append(s.id()).append("] ").append(s.description());
                String r = s.result();
                if (r != null && !r.isBlank()) {
                    String preview = r.length() > 200 ? r.substring(0, 200) + "..." : r;
                    replanPrompt.append("（结果摘要：").append(preview).append("）");
                }
                replanPrompt.append("\n");
            }
        }
        if (!anyCompleted) {
            replanPrompt.append("（无）\n");
        }
        replanPrompt.append("\n请制定新的执行计划，避开之前的问题，不要重复已完成的步骤。")
                .append("输出 JSON 格式，包含 steps 数组，每个 step 有 id/description/type/dependencies 字段。");

        AgentMessage replanMsg = AgentMessage.task("orchestrator", replanPrompt.toString());
        AgentMessage replanResult = planner.execute(replanMsg, out);
        // 不 clearHistory：保留 planner 历史，供后续 replan 继续复用上下文（run 结束时统一清理）

        if (replanResult.type() == AgentMessage.Type.ERROR
                || replanResult.content() == null || replanResult.content().isBlank()) {
            out.println("⚠️ 重规划 LLM 调用失败或输出为空，保留原计划继续执行\n");
            return null;
        }
        // replan 产物覆盖黑板 plan（所有权：PLANNER）
        this.sharedState.setPlan(replanResult.content(), AgentRole.PLANNER);

        List<ExecutionStep> newSteps = parsePlan(replanResult.content());
        if (newSteps.isEmpty()) {
            out.println("⚠️ 重规划输出无法解析为有效计划，保留原计划继续执行\n");
            return null;
        }

        // 合并：保留已完成步骤 + 重命名后的新计划步骤
        String prefix = "r" + replanCount + "_";
        Map<String, String> idMap = new HashMap<>();
        for (ExecutionStep ns : newSteps) {
            idMap.put(ns.id(), prefix + ns.id());
        }
        // 保留已完成步骤，但排除触发 replan 的锚点（EXHAUSTED 路径用 withResult 标成 COMPLETED，
        // 其结果已被审查拒绝，不应作为「已成功」保留；FAILED 路径本就不会进入 COMPLETED）。
        List<ExecutionStep> merged = new ArrayList<>();
        for (ExecutionStep s : steps) {
            if (s.status() == StepStatus.COMPLETED && !s.id().equals(failedStepId)) {
                merged.add(s);
            }
        }
        for (ExecutionStep ns : newSteps) {
            String newId = idMap.get(ns.id());
            List<String> newDeps = new ArrayList<>();
            for (String dep : ns.dependencies()) {
                newDeps.add(idMap.getOrDefault(dep, dep));
            }
            merged.add(new ExecutionStep(newId, ns.description(), ns.type(), newDeps,
                    ns.assignee(), ns.result(), ns.status()));
        }

        out.println(AnsiStyle.heading("📋 重规划后的执行计划"));
        out.println(summarizeSteps(merged) + "\n");
        return merged;
    }

    /**
     * Worker 未产出信封（理论上不会发生，execute 总会 storeLastResult）时的兜底：用裸 prose 造最小信封，
     * 让 Reviewer 的 rubric 路径始终拿到结构化输入。
     */
    private SubAgentResult emptyEnvelope(String summary) {
        return new SubAgentResult("unknown", AgentRole.WORKER, summary,
                List.of(), List.of(), 0.5, false, 0, 0, 0, false, null);
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                // 优先从共享黑板读 artifact（对标 2026 Blackboard：显式共享状态）；
                // 黑板未命中时回退到 step.result()，兼容旧路径。
                String artifact = sharedState != null ? sharedState.getArtifact(step.id()) : null;
                String source = artifact != null ? artifact : step.result();
                if (source != null && !source.isBlank()) {
                    String preview = source.length() > 500
                            ? source.substring(0, 500) + "..."
                            : source;
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    /**
     * 构造发给该 worker 的 peer 留言块（阶段D p2p，对标 agent teams 共享消息）。
     * 从共享黑板读 inbox（含广播），格式化为 "同事留言" 段落注入 worker context。
     * 无留言时返回空串，不影响 context。
     */
    private String buildInboxBlock(String workerName) {
        if (sharedState == null) {
            return "";
        }
        List<SharedState.PeerMessage> inbox = sharedState.getInbox(workerName);
        if (inbox.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("同事留言（peer messages）：\n");
        for (SharedState.PeerMessage msg : inbox) {
            String target = msg.to().isEmpty() ? "所有人" : msg.to();
            sb.append("- 来自 ").append(msg.from()).append(" 给 ").append(target)
                    .append("：").append(msg.content()).append("\n");
        }
        return sb.toString();
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总。
     *
     * 注意：Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }
}
