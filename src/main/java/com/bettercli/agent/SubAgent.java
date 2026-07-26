package com.bettercli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmTraceLogger;
import com.bettercli.lsp.LspDiagnosticReport;
import com.bettercli.memory.CompactConfig;
import com.bettercli.memory.CompactTrigger;
import com.bettercli.memory.CompactionSupport;
import com.bettercli.memory.ConversationHistoryCompactor;
import com.bettercli.context.ContextProfile;
import com.bettercli.prompt.PromptAssembler;
import com.bettercli.prompt.PromptContext;
import com.bettercli.prompt.PromptMode;
import com.bettercli.prompt.ProjectMemoryLoader;
import com.bettercli.runtime.CancellationContext;
import com.bettercli.skill.Skill;
import com.bettercli.skill.SkillContextBuffer;
import com.bettercli.skill.SkillIndexFormatter;
import com.bettercli.skill.SkillRegistry;
import com.bettercli.tool.ToolRegistry;
import com.bettercli.tool.ToolRegistry.ToolExecutionResult;
import com.bettercli.tool.ToolRegistry.ToolInvocation;
import com.bettercli.util.AnsiStyle;
import com.bettercli.util.TerminalMarkdownRenderer;
import com.bettercli.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 子代理 - 可配置角色的轻量 Agent
 *
 * 每个 SubAgent 有独立的角色、系统提示词和对话历史，
 * 但共享 LLM 客户端和工具注册表。
 */
public class SubAgent implements Worker {
    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final String specialty;
    /** Custom SubAgent：专属 system prompt body；非 null 时不再走 TEAM_* PromptMode。 */
    private final String customPromptBody;
    /** Custom SubAgent：可选 MEMORY.md 正文。 */
    private final String customMemoryMd;
    /** Custom SubAgent：工具白名单；非 null 时覆盖 {@link AgentRole#allowedTools()}。 */
    private final Set<String> toolOverride;
    /** Custom SubAgent：硬轮数覆盖；null 表示用默认 AgentBudget。 */
    private final Integer maxTurnsOverride;
    /** Custom SubAgent：Skill 白名单；null/空 = 不限制（全量）；非空则索引与 load 仅限这些。 */
    private final Set<String> skillWhitelist;
    private Supplier<String> externalContextSupplier = () -> "";
    private String teamWorkersContext;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private final ConversationHistoryCompactor historyCompactor;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private Integer lastKnownInputTokens;
    private int currentTurnUserIndex = -1;
    private SubAgentResult lastResult;
    private volatile Runnable turnCheckpointListener;
    private volatile String customSessionId;
    private volatile com.bettercli.subagent.AgentSteerService steerService;
    private volatile java.util.function.Consumer<String> progressListener;

    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this(name, role, llmClient, toolRegistry, null);
    }

    /**
     * @param specialty 角色专长描述（仅 WORKER 角色会注入到 prompt 的 {{workerSpecialty}}），
     *                 用于让多个 Worker 有差异化专长；null/空 表示不注入。
     */
    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry, String specialty) {
        this(name, role, llmClient, toolRegistry, specialty, null, null, null, null, null);
    }

    /**
     * 由 Custom SubAgent 定义构造：独立 prompt / 工具集 / maxTurns，角色标签固定为 WORKER。
     */
    public static SubAgent forCustom(
            String name,
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            String promptBody,
            String memoryMd,
            Set<String> effectiveTools,
            Integer maxTurns) {
        return forCustom(name, llmClient, toolRegistry, promptBody, memoryMd, effectiveTools, maxTurns, null);
    }

    public static SubAgent forCustom(
            String name,
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            String promptBody,
            String memoryMd,
            Set<String> effectiveTools,
            Integer maxTurns,
            Set<String> skillWhitelist) {
        if (effectiveTools == null) {
            throw new IllegalArgumentException("Custom SubAgent effectiveTools 不能为 null");
        }
        return new SubAgent(
                name,
                AgentRole.WORKER,
                llmClient,
                toolRegistry,
                null,
                promptBody == null ? "" : promptBody,
                memoryMd,
                Set.copyOf(effectiveTools),
                maxTurns,
                skillWhitelist == null || skillWhitelist.isEmpty() ? null : Set.copyOf(skillWhitelist));
    }

    private SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry,
                     String specialty, String customPromptBody, String customMemoryMd,
                     Set<String> toolOverride, Integer maxTurnsOverride, Set<String> skillWhitelist) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.specialty = specialty;
        this.customPromptBody = customPromptBody;
        this.customMemoryMd = customMemoryMd;
        this.toolOverride = toolOverride;
        this.maxTurnsOverride = maxTurnsOverride;
        this.skillWhitelist = skillWhitelist;
        // 模型标记改在 execute() 用线程级覆盖，避免并行 SubAgent / Custom SubAgent 竞态改写全局 volatile
        this.conversationHistory = new ArrayList<>();
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.conversationHistory.add(LlmClient.Message.system(getSystemPrompt()));
    }

    /** 是否为 Custom SubAgent 模式（非 Multi-Agent 三角色模板）。 */
    public boolean isCustomMode() {
        return customPromptBody != null;
    }

    Set<String> effectiveToolWhitelist() {
        return toolOverride != null ? toolOverride : role.allowedTools();
    }

    /**
     * 注入团队 Worker 名单与专长描述，供 PLANNER 角色在 prompt 的 {{teamWorkers}} 处使用，
     * 让规划者知道有哪些 Worker 及其专长，从而在计划 JSON 的每个 step 指定 assignee。
     */
    public void setTeamWorkersContext(String teamWorkersContext) {
        this.teamWorkersContext = teamWorkersContext;
        refreshSystemPrompt();
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        refreshSystemPrompt();
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        refreshSystemPrompt();
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    /**
     * 根据角色获取系统提示词；Custom 模式使用专属 body + 可选 MEMORY.md。
     */
    private String getSystemPrompt() {
        if (customPromptBody != null) {
            return buildCustomSystemPrompt();
        }
        return promptAssembler.assemble(promptMode(), PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .variable("workerSpecialty", specialty == null ? "" : specialty)
                .variable("teamWorkers", teamWorkersContext == null ? "" : teamWorkersContext)
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
    }

    private String buildCustomSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(customPromptBody.trim());
        String projectMem = buildProjectMemoryContext();
        if (projectMem != null && !projectMem.isBlank()) {
            sb.append("\n\n## Project Context\n\n").append(projectMem.trim());
        }
        if (customMemoryMd != null && !customMemoryMd.isBlank()) {
            sb.append("\n\n## Subagent Memory\n\n").append(customMemoryMd.trim());
        }
        String skillIndex = buildSkillIndex();
        if (skillIndex != null && !skillIndex.isBlank()) {
            sb.append("\n\n").append(skillIndex.trim());
        }
        sb.append("\n\n## Language\n\n请用中文回复；代码、命令、文件名保留原文。\n");
        sb.append("\n## Subagent Memory Policy\n\n");
        sb.append("跨会话稳定偏好/经验可用 `write_subagent_memory` 追加写入本子 Agent 的 MEMORY.md；")
                .append("不要写入密钥、一次性任务细节或猜测。\n");
        return sb.toString();
    }

    private PromptMode promptMode() {
        return switch (role) {
            case PLANNER -> PromptMode.TEAM_PLANNER;
            case WORKER -> PromptMode.TEAM_WORKER;
            case REVIEWER -> PromptMode.TEAM_REVIEWER;
        };
    }

    private boolean maybeCompactHistory(PrintStream out) {
        return maybeCompactHistory(out, CompactTrigger.PRE_TURN);
    }

    private boolean maybeCompactHistory(PrintStream out, CompactTrigger trigger) {
        if (historyCompactor == null) {
            return false;
        }
        ContextProfile profile = toolRegistry == null ? null : toolRegistry.getContextProfile();
        if (profile == null) {
            profile = ContextProfile.from(llmClient);
        }
        try {
            int userIdx = currentTurnUserIndex;
            if (userIdx < 0 && trigger == CompactTrigger.PRE_TURN) {
                userIdx = CompactionSupport.findLastUserIndex(conversationHistory);
            }
            CompactConfig config = CompactConfig.from(
                    profile,
                    lastKnownInputTokens,
                    CompactionSupport.estimateToolsSchemaTokens(
                            llmClient != null && llmClient.supportsTools()
                                    ? toolRegistry.getToolDefinitions(role.allowedTools())
                                    : null));
            boolean compacted = historyCompactor.compact(
                    conversationHistory, trigger, config, userIdx);
            if (compacted) {
                lastKnownInputTokens = null;
                if (trigger == CompactTrigger.PRE_TURN) {
                    currentTurnUserIndex = CompactionSupport.findLastUserIndex(conversationHistory);
                }
                if (out != null) {
                    String tip = switch (trigger) {
                        case PRE_TURN -> "📦 [" + name + "] Pre-Turn：上下文接近窗口上限，已写入压缩检查点后继续。";
                        case MID_TURN -> "📦 [" + name + "] Mid-Turn：工具结果已纳入摘要检查点，继续下一轮采样。";
                        case PROMPT_TOO_LONG, CONTEXT_WINDOW_EXCEEDED ->
                                "📦 [" + name + "] 模型确认上下文溢出，已强制压缩检查点并重试。";
                        case MANUAL -> "📦 [" + name + "] 已手动写入压缩检查点。";
                    };
                    out.println(tip);
                }
            }
            return compacted;
        } catch (Exception e) {
            log.warn("[{}] conversationHistory compaction failed", name, e);
            return false;
        }
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            List<Skill> skills = skillRegistry.enabledSkills();
            if (skillWhitelist != null && !skillWhitelist.isEmpty()) {
                skills = skills.stream()
                        .filter(s -> skillWhitelist.stream()
                                .anyMatch(w -> w.equalsIgnoreCase(s.name())))
                        .toList();
            }
            return SkillIndexFormatter.format(skills);
        } catch (Exception e) {
            log.warn("[{}] failed to build skill index", name, e);
            return "";
        }
    }

    private String prependSkillBodies(String content) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return content;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return content;
        return drained + "\n" + content;
    }

    private void refreshSystemPrompt() {
        if (!conversationHistory.isEmpty()) {
            conversationHistory.set(0, LlmClient.Message.system(getSystemPrompt()));
        }
    }

    private String buildExternalContext() {
        if (!toolRegistry.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("[{}] failed to build external context", name, e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("[{}] failed to load BETTER.md project memory", name, e);
            return "";
        }
    }

    /**
     * 执行任务，返回结果消息（默认输出到 System.out）
     */
    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    /**
     * 执行任务并将流式输出写入指定 PrintStream。并发执行时为每个步骤传入独立的 PrintStream，
     * 避免多个 Agent 同时写入 System.out 造成输出交错。
     */
    public AgentMessage execute(AgentMessage task, PrintStream out) {
        log.info("[{}] executing task from {}: type={}", name, task.fromAgent(), task.type());
        toolRegistry.setThreadCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        try {
            return executeWithModelBound(task, out);
        } finally {
            toolRegistry.clearThreadCurrentModel();
        }
    }

    private AgentMessage executeWithModelBound(AgentMessage task, PrintStream out) {
        pruneHistoricalImagePayloads();
        refreshSystemPrompt();
        String taskContent = prependSkillBodies(task.content());

        // 将任务注入对话
        conversationHistory.add(ImageReferenceParser.userMessage(
                taskContent,
                Path.of(toolRegistry.getProjectPath())));
        currentTurnUserIndex = conversationHistory.size() - 1;

        SubAgentStreamRenderer streamRenderer = new SubAgentStreamRenderer(name, role, out);

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient, maxTurnsOverride);
        boolean preTurnDone = false;
        boolean overflowRetryUsed = false;

        // 与 Agent.java 对称：主退出条件 = LLM 自决，budget 仅在 token / 停滞 / 硬轮数兜底。
        while (true) {
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                String msg = "用户取消";
                log.info("[{}] cancelled by CancellationContext", name);
                storeLastResult(msg, true, msg, budget, false);
                return AgentMessage.error(name, role, msg);
            }

            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                streamRenderer.finish();
                String description = budget.describeExit(exitReason);
                log.warn("[{}] run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        name, exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                storeLastResult(description, true, description, budget, true);
                return AgentMessage.error(name, role, description);
            }

            budget.beginIteration();
            touchProgress("iteration " + budget.iteration());

            injectPendingLspDiagnostics(out);
            if (!preTurnDone) {
                maybeCompactHistory(out, CompactTrigger.PRE_TURN);
                preTurnDone = true;
            }

            try {
                List<LlmClient.Message> requestMessages = conversationHistory;
                com.bettercli.subagent.AgentSteerService steers = this.steerService;
                String sessionId = this.customSessionId;
                if (steers != null && sessionId != null && !sessionId.isBlank()) {
                    List<String> pendingSteers = steers.drain(sessionId);
                    if (!pendingSteers.isEmpty()) {
                        requestMessages = new ArrayList<>(conversationHistory);
                        for (String steer : pendingSteers) {
                            requestMessages.add(LlmClient.Message.user(
                                    "[steer — ephemeral, not persisted]\n" + steer));
                        }
                        touchProgress("steer×" + pendingSteers.size());
                    }
                }

                LlmClient.ChatResponse response = llmClient.chat(
                        requestMessages,
                        llmClient.supportsTools() ? toolRegistry.getToolDefinitions(effectiveToolWhitelist()) : null,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log,
                        "sub-agent name=" + name + " role=" + role + " iteration=" + budget.iteration(),
                        llmClient,
                        response.reasoningContent());

                if (CancellationContext.isCancelled()) {
                    streamRenderer.finish();
                    String msg = "用户取消";
                    storeLastResult(msg, true, msg, budget, false);
                    return AgentMessage.error(name, role, msg);
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
                if (response.inputTokens() > 0) {
                    lastKnownInputTokens = response.inputTokens();
                }
                overflowRetryUsed = false;

                if (response.hasToolCalls()) {
                    budget.recordToolCalls(response.toolCalls());
                    printToolCalls(out, response.toolCalls());
                    for (LlmClient.ToolCall tc : response.toolCalls()) {
                        touchProgress(tc.function().name());
                    }
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前 flush 并重置流式渲染器：TerminalMarkdownRenderer 按换行 flush，
                    // 没有换行的 pending 内容会被 HITL 提示"跨过"导致标题错位。
                    streamRenderer.resetBetweenIterations();

                    touchProgress("tools…");
                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls());
                    touchProgress("tools done");
                    for (ToolExecutionResult toolResult : toolResults) {
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    appendImageToolMessages(toolResults);
                    maybeCompactHistory(out, CompactTrigger.MID_TURN);
                    fireTurnCheckpoint();
                    continue;
                }

                // 没有工具调用，返回最终结果
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                streamRenderer.finish();

                storeLastResult(response.content(), false, null, budget, false);
                fireTurnCheckpoint();
                return AgentMessage.result(name, role, response.content());

            } catch (IOException e) {
                CompactTrigger fallback = CompactionSupport.overflowTrigger(e);
                if (fallback != null && !overflowRetryUsed) {
                    overflowRetryUsed = true;
                    log.warn("[{}] LLM context overflow ({}), compact-and-retry once", name, fallback);
                    streamRenderer.resetBetweenIterations();
                    if (maybeCompactHistory(out, fallback)) {
                        continue;
                    }
                }
                log.error("[{}] LLM call failed", name, e);
                streamRenderer.finish();
                String errorMsg = "LLM 调用失败: " + e.getMessage();
                storeLastResult(errorMsg, true, errorMsg, budget, false);
                return AgentMessage.error(name, role, errorMsg);
            }
        }
    }

    /**
     * 从运行轨迹确定性派生结构化交接信封并存为 {@link #lastResult}。
     * 取舍：不依赖 LLM 自报 JSON，而从工具调用参数 / 退出状态客观派生，可观测且抗自证偏差。
     */
    private void storeLastResult(String summary, boolean error, String errorMsg,
                                 AgentBudget budget, boolean exhausted) {
        List<String> artifacts = collectArtifacts();
        List<String> issues = error
                ? List.of(errorMsg == null ? "执行出错" : errorMsg)
                : collectIssues();
        double confidence = error ? 0.2 : exhausted ? 0.5 : 0.85;
        this.lastResult = new SubAgentResult(name, role, summary, artifacts, issues, confidence,
                exhausted, budget.iteration(), budget.totalInputTokens(), budget.totalOutputTokens(),
                error, error ? errorMsg : null);
        log.info("[{}] produced envelope: {}", name, lastResult.oneLineSummary());
    }

    /**
     * 从对话历史里提取 Worker 触发改动的文件/项目（write_file 的 path / create_project 的 name）。
     */
    private List<String> collectArtifacts() {
        List<String> artifacts = new java.util.ArrayList<>();
        for (LlmClient.Message msg : conversationHistory) {
            if (msg.toolCalls() == null) continue;
            for (LlmClient.ToolCall tc : msg.toolCalls()) {
                String toolName = tc.function().name();
                String args = tc.function().arguments();
                String key = switch (toolName) {
                    case "write_file" -> "path";
                    case "create_project" -> "name";
                    default -> null;
                };
                if (key == null) continue;
                try {
                    String value = JSON_MAPPER.readTree(args).path(key).asText("");
                    if (!value.isBlank() && !artifacts.contains(value)) {
                        artifacts.add(value);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return List.copyOf(artifacts);
    }

    /**
     * 从对话历史里提取工具结果中的错误信号（启发式：含 ❌/失败/错误/error）。
     */
    private List<String> collectIssues() {
        List<String> issues = new java.util.ArrayList<>();
        for (LlmClient.Message msg : conversationHistory) {
            if (!"tool".equals(msg.role()) || msg.content() == null) continue;
            String c = msg.content();
            if (c.contains("❌") || c.contains("失败") || c.contains("错误")
                    || c.toLowerCase().contains("error")) {
                String snippet = c.length() > 120 ? c.substring(0, 117) + "..." : c;
                if (!issues.contains(snippet)) {
                    issues.add(snippet);
                }
            }
        }
        return List.copyOf(issues);
    }

    /**
     * 返回最近一次 {@link #execute} 产出的结构化交接信封；未执行过返回 null。
     */
    public SubAgentResult lastRunResult() {
        return lastResult;
    }

    /**
     * 带评分标准（rubric）的对抗式审查：把 Worker 的结构化信封（而非裸 prose）交给 Reviewer，
     * 明确要求 Reviewer 不要只信执行者自述——artifacts 非空或 confidence < 0.7 时必须实际
     * read_file/grep_code 核实被改文件，降低自证偏差（对标 2026 independent grading 模式）。
     */
    public AgentMessage reviewWithRubric(String originalTask, SubAgentResult workerEnvelope, PrintStream out) {
        String artifactsStr = workerEnvelope.artifacts() == null || workerEnvelope.artifacts().isEmpty()
                ? "无" : String.join(", ", workerEnvelope.artifacts());
        String issuesStr = workerEnvelope.issues() == null || workerEnvelope.issues().isEmpty()
                ? "无" : String.join(" | ", workerEnvelope.issues());
        String rubricInput = "原始任务：" + originalTask
                + "\n\n执行者自报摘要：\n" + (workerEnvelope.summary() == null ? "" : workerEnvelope.summary())
                + "\n\n执行者触发的文件改动（artifacts）：" + artifactsStr
                + "\n执行者观察到的问题（issues）：" + issuesStr
                + "\n执行者置信度（confidence）：" + String.format("%.2f", workerEnvelope.confidence())
                + "\n\n审查要求（rubric）："
                + "\n1. 不要只信执行者自报摘要——若 artifacts 非空，必须用 read_file / grep_code 实际读取被改文件核实，避免执行者自证偏差。"
                + "\n2. 若 confidence < 0.7 或任务涉及文件写入，必须实际验证。"
                + "\n3. 判断：通过 / 不通过 + 具体问题。";
        AgentMessage reviewTask = AgentMessage.task("orchestrator", rubricInput);
        return execute(reviewTask, out);
    }

    /**
     * 执行任务（带上下文注入），用于 Worker 接收额外上下文
     */
    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, System.out);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String enrichedContent = task.content();
        if (context != null && !context.isEmpty()) {
            enrichedContent = context + "\n\n当前任务：" + task.content();
        }
        AgentMessage enrichedTask = new AgentMessage(task.fromAgent(), task.fromRole(),
                enrichedContent, task.type());
        return execute(enrichedTask, out);
    }

    /**
     * 检查结果（Reviewer 专用）
     */
    public AgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String reviewInput = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        AgentMessage reviewTask = AgentMessage.task("orchestrator", reviewInput);
        return execute(reviewTask, out);
    }

    /**
     * 清空对话历史（保留系统提示词），用于处理下一个独立任务
     */
    public void clearHistory() {
        LlmClient.Message systemMsg = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
    }

    /** 当前对话快照（含 system），供 Custom SubAgent 会话落盘 / 续跑。 */
    public List<LlmClient.Message> snapshotHistory() {
        return List.copyOf(conversationHistory);
    }

    /**
     * 用已保存的历史覆盖当前对话（保留/刷新本 Agent 的 system prompt 为第 0 条）。
     * 跳过快照里的旧 system，避免串 prompt。
     */
    public void restoreHistory(List<LlmClient.Message> saved) {
        LlmClient.Message systemMsg = LlmClient.Message.system(getSystemPrompt());
        conversationHistory.clear();
        conversationHistory.add(systemMsg);
        if (saved == null || saved.isEmpty()) {
            return;
        }
        for (LlmClient.Message m : saved) {
            if (m == null || "system".equalsIgnoreCase(m.role())) {
                continue;
            }
            conversationHistory.add(m);
        }
    }

    /** Custom SubAgent 会话 id，供 steer / 进度回调。 */
    public void setCustomSessionId(String customSessionId) {
        this.customSessionId = customSessionId;
    }

    public void setSteerService(com.bettercli.subagent.AgentSteerService steerService) {
        this.steerService = steerService;
    }

    public void setProgressListener(java.util.function.Consumer<String> progressListener) {
        this.progressListener = progressListener;
    }

    private void touchProgress(String detail) {
        java.util.function.Consumer<String> listener = progressListener;
        if (listener != null) {
            try {
                listener.accept(detail);
            } catch (Exception ignored) {
            }
        }
    }

    /** 每轮工具批次回填后或即将结束时回调（Custom HA 落盘用）；失败忽略。 */
    public void setTurnCheckpointListener(Runnable listener) {
        this.turnCheckpointListener = listener;
    }

    private void fireTurnCheckpoint() {
        Runnable listener = turnCheckpointListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Exception e) {
            log.debug("[{}] turn checkpoint failed: {}", name, e.getMessage());
        }
    }

    /**
     * 路由/直达模式：把主会话近期 user/assistant 消息 seed 进本子 Agent（保留本 Agent system prompt）。
     * 跳过 system / tool / 含 tool_calls 的 assistant；截断过长 content。
     */
    public void seedParentHistory(List<LlmClient.Message> parentMessages, int maxMessages) {
        if (parentMessages == null || parentMessages.isEmpty()) {
            return;
        }
        int limit = Math.max(1, maxMessages);
        List<LlmClient.Message> selected = new ArrayList<>();
        for (LlmClient.Message m : parentMessages) {
            if (m == null) {
                continue;
            }
            String role = m.role() == null ? "" : m.role().toLowerCase(java.util.Locale.ROOT);
            if ("system".equals(role) || "tool".equals(role)) {
                continue;
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                continue;
            }
            String content = m.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "...";
            }
            if ("user".equals(role)) {
                selected.add(LlmClient.Message.user(content));
            } else if ("assistant".equals(role)) {
                selected.add(LlmClient.Message.assistant(content));
            }
        }
        if (selected.size() > limit) {
            selected = selected.subList(selected.size() - limit, selected.size());
        }
        int insertAt = conversationHistory.isEmpty() ? 0 : 1;
        conversationHistory.addAll(insertAt, selected);
        log.debug("[{}] seeded {} parent messages", name, selected.size());
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("[{}] pruned historical image payloads before sub-agent turn: messages={}, images={}",
                    name, messageCount, imageCount);
        }
    }

    /**
     * 角色工具白名单说明：
     * - WORKER   返回 null  → 不限制，全部内置工具 + MCP 工具可用
     * - PLANNER  返回只读+调研集合 → 只能读/查，不能写/执行/联网改状态
     * - REVIEWER 返回只读集合 → 只能读代码核实，不能联网/写/执行
     *
     * 白名单在两处生效：
     * 1. {@code getToolDefinitions(whitelist)} 只把白名单内工具的 schema 下发给 LLM
     * 2. {@code executeTools(invocations, whitelist)} 在执行层拦截越权调用（防御 LLM 幻觉出白名单外的工具名）
     */

    private void injectPendingLspDiagnostics(PrintStream out) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        out.println(report.displayText());
        log.info("[{}] injected LSP diagnostics into sub-agent conversation", name);
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("[{}] scheduling tool: {}", name, toolName);
            log.debug("[{}] tool args [{}]: {}", name, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("[{}] executing {} tool calls in parallel", name, invocations.size());
        }
        return toolRegistry.executeTools(invocations, effectiveToolWhitelist());
    }

    private void appendImageToolMessages(List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            conversationHistory.add(LlmClient.Message.user(parts));
        }
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    public String getSpecialty() {
        return specialty;
    }

    /**
     * SubAgent 流式渲染器，分区展示 reasoning_content 与 content。
     *
     * 与 {@link com.bettercli.agent.Agent.StreamRenderer} 使用同一策略应对
     * "content 开始后又追加 reasoning"的场景：迟到的 reasoning 会被累积到 lateReasoning，
     * 在 finish() 时以"🧠 补充思考"独立展示，避免混入结果区。
     */
    private static final class SubAgentStreamRenderer implements LlmClient.StreamListener {
        private final String agentName;
        private final AgentRole role;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private SubAgentStreamRenderer(String agentName, AgentRole role, PrintStream out) {
            this.agentName = agentName;
            this.role = role;
            this.out = out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    // 实质 reasoning 尚未流出就被 content 打断：先补打思考过程再切到结果
                    out.println(AnsiStyle.heading("🧠 " + reasoningLabel() + " [" + agentName + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out.println(AnsiStyle.section("🤖 " + contentLabel() + " [" + agentName + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private String reasoningLabel() {
            return switch (role) {
                case PLANNER -> "规划思考";
                case WORKER -> "执行思考";
                case REVIEWER -> "审查思考";
            };
        }

        private String contentLabel() {
            // 故意区分：PLANNER/REVIEWER 不调用工具，content 一定是最终输出，用"结果"；
            // WORKER 可能在 tool_calls 前先 narrate，用"输出"避免"结果"暗示已经完成。
            return switch (role) {
                case PLANNER -> "规划结果";
                case WORKER -> "执行输出";
                case REVIEWER -> "审查结果";
            };
        }

        /**
         * 在两次迭代（通常是 tool-call 分支）之间调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代的 reasoning/content 能重新打印各自的标题。
         */
        private void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out.println();
                out.println(AnsiStyle.heading("🧠 补充思考 [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private void finish() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            String late = lateReasoning.toString().trim();
            if (!late.isEmpty()) {
                out.println();
                out.println(AnsiStyle.heading("🧠 补充思考 [" + agentName + "]"));
                TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out.println("\n");
            }
        }
    }
}
