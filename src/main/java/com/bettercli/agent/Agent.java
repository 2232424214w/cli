package com.bettercli.agent;

import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmTraceLogger;
import com.bettercli.context.ContextProfile;
import com.bettercli.context.TokenUsageFormatter;
import com.bettercli.lsp.LspDiagnosticReport;
import com.bettercli.memory.ConversationHistoryCompactor;
import com.bettercli.memory.ExplicitMemoryHints;
import com.bettercli.memory.MemoryManager;
import com.bettercli.memory.SessionMessageIndexer;
import com.bettercli.prompt.PromptAssembler;
import com.bettercli.prompt.PromptContext;
import com.bettercli.prompt.PromptMode;
import com.bettercli.prompt.ProjectMemoryLoader;
import com.bettercli.render.PlainRenderer;
import com.bettercli.render.Renderer;
import com.bettercli.render.StatusInfo;
import com.bettercli.runtime.CancellationContext;
import com.bettercli.skill.SkillContextBuffer;
import com.bettercli.skill.SkillIndexFormatter;
import com.bettercli.skill.SkillRegistry;
import com.bettercli.subagent.BgReactCoordinator;
import com.bettercli.subagent.CustomSubAgentCompletionEvent;
import com.bettercli.subagent.CustomSubAgentCompletionNotice;
import com.bettercli.subagent.CustomSubAgentIndexFormatter;
import com.bettercli.subagent.CustomSubAgentRunner;
import com.bettercli.util.AnsiStyle;
import com.bettercli.tool.ToolRegistry;
import com.bettercli.tool.ToolRegistry.ToolExecutionResult;
import com.bettercli.tool.ToolRegistry.ToolInvocation;
import com.bettercli.util.TerminalMarkdownRenderer;
import com.bettercli.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private CustomSubAgentRunner customSubAgentRunner;
    private final BgReactCoordinator bgReactCoordinator = new BgReactCoordinator();
    private final java.util.concurrent.atomic.AtomicBoolean inRun =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 会话世代：/clear 递增，使已排队的 bg-react 与迟到的完成通知注入失效
     * （单进程等价于 1024 `/new` 清 Redis running key）。
     */
    private final java.util.concurrent.atomic.AtomicLong sessionEpoch =
            new java.util.concurrent.atomic.AtomicLong(0);
    /** 微信等通道默认后台委托；CLI 默认前台。 */
    private volatile boolean subagentBackgroundDefault;
    /** bg-react 汇总文本消费者（微信推送）；null 则只走 renderer。 */
    private volatile java.util.function.Consumer<String> bgReactReplyConsumer;
    private Renderer renderer;
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    private boolean returnFinalResponseWhenStreamed;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private SessionMessageIndexer sessionMessageIndexer;
    /** 无 SessionMessageIndexer 时的父会话 id（如微信通道）。 */
    private volatile String fallbackConversationId;
    // ReAct 轻量规划存储（对标 Claude Code TodoWrite）。会话级内存态，不持久化；
    // 每次 Agent 实例独立持有，通过 toolRegistry 注入给 update_plan 工具读写。
    private final PlanStore planStore = new PlanStore();
    // 会话结构化记事本（Anthropic structured note-taking）。会话级内存态；/clear 清空。
    private final SessionNotebook sessionNotebook = new SessionNotebook();
    // 工具失败反思服务（阶段1：轻量反思，不额外调 LLM）。会话级有状态，持有反螺旋计数器。
    // 在 executeToolCalls 之后检测失败并注入反思提示，连续反思超阈值后停止，交给 budget 兜底。
    private final ReflectionService reflectionService = new ReflectionService();
    /** run_team 嵌套深度，防止团队工具递归调用自己。 */
    private final java.util.concurrent.atomic.AtomicInteger teamNesting = new java.util.concurrent.atomic.AtomicInteger();

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        this.toolRegistry.setPlanStore(this.planStore);
        this.toolRegistry.setSessionNotebook(this.sessionNotebook);
        wireModeCapabilityTools();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.historyCompactor.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        wireModeCapabilityTools();
    }

    /**
     * 把 Planner / AgentOrchestrator / Custom SubAgent 挂成 ReAct 工具（模式统一：单入口 + 按需调用）。
     */
    private void wireModeCapabilityTools() {
        toolRegistry.setModeCapabilityHandlers(this::createPlanViaTool, this::runTeamViaTool);
        toolRegistry.setRunSubagentHandler(this::runSubagentViaTool);
        toolRegistry.setRunningAgentManagementHandlers(
                this::runningAgentsListViaTool,
                this::terminateAgentViaTool,
                this::steerAgentViaTool);
    }

    /**
     * 注入 Custom SubAgent 运行时（语义委托 run_subagent）。与 /team Multi-Agent 独立。
     */
    public void setCustomSubAgentRunner(CustomSubAgentRunner customSubAgentRunner) {
        this.customSubAgentRunner = customSubAgentRunner;
        if (customSubAgentRunner != null) {
            customSubAgentRunner.setCompletionListener(this::onSubAgentBackgroundComplete);
        }
        toolRegistry.setRunningAgentManagementHandlers(
                this::runningAgentsListViaTool,
                this::terminateAgentViaTool,
                this::steerAgentViaTool);
        refreshSystemPromptAfterSubagentChange();
    }

    private String runningAgentsListViaTool() {
        if (customSubAgentRunner == null) {
            return "running_agents_list 失败: Custom SubAgent 未初始化";
        }
        String parentId = sessionMessageIndexer == null
                ? fallbackConversationId : sessionMessageIndexer.getConversationId();
        return customSubAgentRunner.formatRunningTree(parentId);
    }

    private String terminateAgentViaTool(String conversationId) {
        if (customSubAgentRunner == null) {
            return "terminate_agent 失败: Custom SubAgent 未初始化";
        }
        return customSubAgentRunner.terminateAgent(conversationId);
    }

    private String steerAgentViaTool(String conversationId, String message) {
        if (customSubAgentRunner == null) {
            return "steer_agent 失败: Custom SubAgent 未初始化";
        }
        return customSubAgentRunner.steerAgent(conversationId, message);
    }

    /** 微信等通道可设为 true，使 run_subagent 默认 background。 */
    public void setSubagentBackgroundDefault(boolean backgroundDefault) {
        this.subagentBackgroundDefault = backgroundDefault;
    }

    /** bg-react 结果推送（如微信 send）；未设置时走 renderer.stream。 */
    public void setBgReactReplyConsumer(java.util.function.Consumer<String> consumer) {
        this.bgReactReplyConsumer = consumer;
    }

    private void refreshSystemPromptAfterSubagentChange() {
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt("")));
        }
    }

    private String createPlanViaTool(String goal) {
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            PrintStream planOut = new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8);
            com.bettercli.plan.Planner planner = new com.bettercli.plan.Planner(llmClient, planOut);
            planner.setProjectMemorySupplier(this::buildProjectMemoryContext);
            com.bettercli.plan.ExecutionPlan plan = planner.createPlan(goal);
            StringBuilder sb = new StringBuilder();
            sb.append("已生成执行计划（create_plan，未自动执行）。\n");
            sb.append("目标：").append(plan.getGoal()).append("\n");
            if (plan.getSummary() != null && !plan.getSummary().isBlank()) {
                sb.append("摘要：").append(plan.getSummary()).append("\n");
            }
            sb.append("任务列表：\n");
            for (com.bettercli.plan.Task task : plan.getAllTasks()) {
                sb.append("- [").append(task.getId()).append("] (").append(task.getType()).append(") ")
                        .append(task.getDescription());
                if (!task.getDependencies().isEmpty()) {
                    sb.append("  deps=").append(task.getDependencies());
                }
                sb.append("\n");
            }
            sb.append("\n请按依赖顺序逐步用工具完成；需要完整审阅执行流时可用 /plan。");
            return sb.toString();
        } catch (Exception e) {
            return "create_plan 失败: " + e.getMessage();
        }
    }

    private String runTeamViaTool(String goal) {
        if (teamNesting.get() > 0) {
            return "run_team 失败: 不可嵌套调用（团队执行中）";
        }
        teamNesting.incrementAndGet();
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            PrintStream teamOut = new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8);
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient, toolRegistry, memoryManager, teamOut);
            String result = orchestrator.run(goal);
            String logs = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("【Multi-Agent 团队结果】\n");
            if (result != null && !result.isBlank()) {
                sb.append(result.trim()).append("\n");
            }
            if (logs != null && !logs.isBlank()) {
                String trimmed = logs.length() > 4000 ? logs.substring(logs.length() - 4000) : logs;
                sb.append("\n--- 执行摘录 ---\n").append(trimmed.trim());
            }
            return sb.toString();
        } catch (Exception e) {
            return "run_team 失败: " + e.getMessage();
        } finally {
            teamNesting.decrementAndGet();
        }
    }

    private String runSubagentViaTool(String name, String task, String mode) {
        if (customSubAgentRunner == null) {
            return "run_subagent 失败: Custom SubAgent 未初始化";
        }
        PrintStream progress = null;
        try {
            progress = renderer().stream();
        } catch (Exception e) {
            log.debug("Custom SubAgent progress stream unavailable: {}", e.getMessage());
        }
        String parentConversationId = sessionMessageIndexer == null
                ? fallbackConversationId : sessionMessageIndexer.getConversationId();
        boolean background = resolveSubagentBackground(mode);
        return customSubAgentRunner.startAsync(
                name, task, llmClient, toolRegistry, progress, parentConversationId, background, null);
    }

    private boolean resolveSubagentBackground(String mode) {
        if (mode != null && !mode.isBlank()) {
            String m = mode.trim().toLowerCase(java.util.Locale.ROOT);
            if ("background".equals(m) || "bg".equals(m) || "async".equals(m)) {
                return true;
            }
            if ("foreground".equals(m) || "fg".equals(m) || "sync".equals(m)) {
                return false;
            }
        }
        String env = System.getProperty("bettercli.subagent.default.mode");
        if (env == null || env.isBlank()) {
            env = System.getenv("BETTERCLI_SUBAGENT_DEFAULT_MODE");
        }
        if (env != null && !env.isBlank()) {
            String m = env.trim().toLowerCase(java.util.Locale.ROOT);
            if ("background".equals(m) || "bg".equals(m)) {
                return true;
            }
            if ("foreground".equals(m) || "fg".equals(m)) {
                return false;
            }
        }
        return subagentBackgroundDefault;
    }

    private void onSubAgentBackgroundComplete(CustomSubAgentCompletionEvent event) {
        if (event == null) {
            return;
        }
        final long epochAtEnqueue = sessionEpoch.get();
        String notice = CustomSubAgentCompletionNotice.format(event);
        synchronized (conversationHistory) {
            if (sessionEpoch.get() != epochAtEnqueue) {
                return;
            }
            conversationHistory.add(LlmClient.Message.user(notice));
        }
        String parentId = event.parentConversationId();
        bgReactCoordinator.markSessionWrite(parentId);
        bgReactCoordinator.enqueue(parentId, new BgReactCoordinator.BgReactTask() {
            @Override
            public String run() throws Exception {
                if (sessionEpoch.get() != epochAtEnqueue) {
                    log.info("bg-react skip: session cleared (epoch {} -> {})",
                            epochAtEnqueue, sessionEpoch.get());
                    return "";
                }
                waitUntilAgentIdle(60_000);
                if (sessionEpoch.get() != epochAtEnqueue) {
                    return "";
                }
                if (CancellationContext.isCancelled()) {
                    return "";
                }
                return runBgReactTurn();
            }

            @Override
            public void onReply(String reply) {
                if (sessionEpoch.get() != epochAtEnqueue) {
                    return;
                }
                deliverBgReactReply(reply);
            }
        });
    }

    private void waitUntilAgentIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMs);
        while (inRun.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private String runBgReactTurn() {
        log.info("bg-react start");
        String prompt = "[bg-react] 请根据最新的 SubAgent 完成通知处理："
                + "若所有预期子任务已完成则汇总回复用户；"
                + "若仍有未完成则只输出空或极短确认且不要重复已推送内容；"
                + "若结果已在之前回复中体现则静默（可只回复 OK）。";
        return run(prompt);
    }

    private void deliverBgReactReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        String trimmed = reply.trim();
        if ("OK".equalsIgnoreCase(trimmed) || "ok.".equalsIgnoreCase(trimmed)) {
            return;
        }
        java.util.function.Consumer<String> consumer = bgReactReplyConsumer;
        if (consumer != null) {
            try {
                consumer.accept(trimmed);
                return;
            } catch (Exception e) {
                log.debug("bg-react consumer failed: {}", e.getMessage());
            }
        }
        try {
            renderer().stream().println(trimmed);
        } catch (Exception e) {
            log.debug("bg-react renderer failed: {}", e.getMessage());
        }
    }

    /** 暴露 ReAct 规划存储，供渲染层/状态栏展示进度；不应被外部修改。 */
    public PlanStore getPlanStore() {
        return planStore;
    }

    public SessionNotebook getSessionNotebook() {
        return sessionNotebook;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setReturnFinalResponseWhenStreamed(boolean returnFinalResponseWhenStreamed) {
        this.returnFinalResponseWhenStreamed = returnFinalResponseWhenStreamed;
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来。
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /**
     * 注入会话消息索引器，每轮对话结束后异步把新增消息索引到 {@link SessionMessageStore}。
     * 不注入则跳过历史会话检索能力。
     */
    public void setSessionMessageIndexer(SessionMessageIndexer indexer) {
        this.sessionMessageIndexer = indexer;
    }

    /** 无会话索引时仍可把 parentConversationId 写入 Custom SubAgent 审计/落盘。 */
    public void setFallbackConversationId(String conversationId) {
        this.fallbackConversationId = conversationId == null || conversationId.isBlank()
                ? null : conversationId.trim();
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        inRun.set(true);
        try {
            return runInternal(userInput);
        } finally {
            inRun.set(false);
        }
    }

    private String runInternal(String userInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        pruneHistoricalImagePayloads();
        // 存入短期记忆
        memoryManager.addUserMessage(userInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        // 添加用户输入到历史（如有 skill body 注入，前置到原文之前）
        String userMessageContent = prependSkillBodies(userInput);
        conversationHistory.add(ImageReferenceParser.userMessage(
                userMessageContent,
                Path.of(toolRegistry.getProjectPath())));
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在 token 用尽 / 检测到死循环 / 超出硬轮数时兜底。
        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                pushStatus(budget, startNanos, "idle");
                return "⏹️ 已取消当前任务。";
            }
            // 调 LLM 前评估 conversationHistory 是否接近 window 上限；超阈值就把早期消息压缩成摘要。
            // 这是与第 3 期 Memory 短期记忆压缩并行的另一道压缩——后者只压 shortTermMemory，
            // 真正决定下一轮 LLM input token 的是这里。
            injectPendingLspDiagnostics();
            maybeCompactHistory();
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                String description = budget.describeExit(exitReason);
                log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                        exitReason, budget.iteration(),
                        budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
                pushStatus(budget, startNanos, "idle");
                return "❌ " + description;
            }

            int iteration = budget.beginIteration();

            try {
                List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                        ? toolRegistry.getToolDefinitions()
                        : null;
                logRequestContext("react iteration=" + iteration, toolDefinitions);
                streamRenderer.beginThinking();
                // 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolDefinitions,
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    return "⏹️ 已取消当前任务。";
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
                    // 添加助手消息（包含工具调用）
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ));

                    // 在工具执行前就 flush 本轮流式渲染器，避免 TerminalMarkdownRenderer
                    // 内部 pending 缓冲区（仅按换行 flush）里的文本被 HITL 提示"跨过"
                    // 造成标题和内容错位。重置后下一轮迭代的 reasoning/content 会重新打印标题。
                    streamRenderer.resetBetweenIterations();
                    renderer().appendToolCalls(response.toolCalls());

                    List<ToolExecutionResult> toolResults = executeToolCalls(response.toolCalls(), iteration);
                    for (ToolExecutionResult toolResult : toolResults) {
                        memoryManager.addToolResult(toolResult.name(), toolResult.result());
                        conversationHistory.add(LlmClient.Message.tool(toolResult.id(), toolResult.result()));
                    }
                    appendImageToolMessages(toolResults);
                    // 工具失败反思：本轮若有失败/拒绝/超时，注入反思提示引导 LLM 复述原因 + 改换策略
                    maybeInjectReflection(toolResults, iteration);
                    pushStatus(budget, startNanos, "running");

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;
                }

                // 没有工具调用，直接返回结果
                appendReasoning(reasoningTranscript, response.reasoningContent());
                conversationHistory.add(LlmClient.Message.assistant(response.content()));

                // 存入记忆
                memoryManager.addAssistantMessage(response.content());

                // 异步索引本轮新增消息到 SessionMessageStore（不阻塞主路径）
                if (sessionMessageIndexer != null) {
                    try {
                        sessionMessageIndexer.indexIncremental(new ArrayList<>(conversationHistory));
                    } catch (Exception e) {
                        log.debug("异步索引会话消息失败: {}", e.getMessage());
                    }
                }

                // 记录 token 使用
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    return returnFinalResponseWhenStreamed ? (response.content() == null ? "" : response.content().trim()) : "";
                }
                streamRenderer.clearThinkingPanel();
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content());

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目。
     * 同时静默取消未完成的 Custom SubAgent，并递增 sessionEpoch 使已排队 bg-react 失效。
     */
    public void clearHistory() {
        // 先静默取消进行中的委托（其 finally 仍可能回调完成通知），再递增 epoch 使已入队 bg-react 失效
        if (customSubAgentRunner != null) {
            customSubAgentRunner.cancelAllPending(false);
        }
        sessionEpoch.incrementAndGet();
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));

        // 清空短期记忆
        memoryManager.clearShortTerm();
        if (skillContextBuffer != null) {
            skillContextBuffer.clear();
        }
        // 清空 ReAct 规划（对标 /clear 清空其它会话级状态）
        planStore.clear();
        sessionNotebook.clear();
    }

    /** 测试可见：当前会话世代（/clear 后递增）。 */
    long sessionEpoch() {
        return sessionEpoch.get();
    }

    /**
     * 切换 UI/LLM 语言后重建首条 system prompt，使 Language 策略立即生效。
     */
    public void refreshSystemPrompt() {
        LlmClient.Message system = LlmClient.Message.system(buildSystemPrompt(""));
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(system);
            return;
        }
        if ("system".equalsIgnoreCase(conversationHistory.get(0).role())) {
            conversationHistory.set(0, system);
        } else {
            conversationHistory.add(0, system);
        }
    }

    /**
     * 手动压缩当前 ReAct 对话历史，不等待上下文窗口阈值触发。
     */
    public CompactionResult compactHistoryNow() {
        long beforeTokens = estimateCurrentContextTokens();
        try {
            boolean compacted = historyCompactor.compactNow(conversationHistory);
            return new CompactionResult(compacted, beforeTokens, estimateCurrentContextTokens(), null);
        } catch (Exception e) {
            log.warn("manual conversationHistory compaction failed", e);
            return new CompactionResult(false, beforeTokens, estimateCurrentContextTokens(), e.getMessage());
        }
    }

    public record CompactionResult(boolean compacted, long beforeTokens, long afterTokens, String error) {
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase);
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        conversationHistory.set(0, LlmClient.Message.system(buildSystemPrompt(memoryContext)));
    }

    private String buildSystemPrompt(String memoryContext) {
        String assembled = promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
        String subagentIndex = buildCustomSubAgentIndex();
        if (subagentIndex == null || subagentIndex.isBlank()) {
            return assembled;
        }
        return assembled + "\n\n" + subagentIndex.trim();
    }

    private void maybeCompactHistory() {
        if (historyCompactor == null) return;
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        try {
            boolean compacted = historyCompactor.compactIfNeeded(conversationHistory, trigger);
            if (compacted) {
                renderer().stream().println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
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
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    private void injectPendingLspDiagnostics() {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(report.promptText()));
        renderer().stream().println(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }

    /**
     * 工具失败反思注入（阶段1：轻量，不额外调 LLM）。
     *
     * <p>检测本轮工具结果，若出现失败/拒绝/超时，由 {@link ReflectionService}
     * 构造反思提示并注入 conversationHistory（作为 user message），引导 LLM
     * 复述错误原因 + 改换策略，而非原样重试。反螺旋由 ReflectionService 内部计数器
     * 控制，超阈值后返回 null 不注入，交给 AgentBudget stagnation 兜底。
     */
    private void maybeInjectReflection(List<ToolExecutionResult> toolResults, int iteration) {
        String prompt = reflectionService.buildReflectionPrompt(toolResults, iteration);
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        conversationHistory.add(LlmClient.Message.user(prompt));
        renderer().stream().println(AnsiStyle.subtle("  ↻ " + prompt.split("\n", 2)[0]));
        log.info("Injected reflection prompt at iteration={} (consecutive={})",
                iteration, reflectionService.consecutiveReflections());
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String buildCustomSubAgentIndex() {
        if (customSubAgentRunner == null || customSubAgentRunner.registry() == null) {
            return "";
        }
        try {
            return CustomSubAgentIndexFormatter.format(customSubAgentRunner.registry().all());
        } catch (Exception e) {
            log.warn("Failed to build Custom SubAgent index", e);
            return "";
        }
    }

    private String prependSkillBodies(String userInput) {
        if (skillContextBuffer == null || skillContextBuffer.isEmpty()) {
            return userInput;
        }
        String drained = skillContextBuffer.drain();
        if (drained.isEmpty()) return userInput;
        return drained + "\n用户输入：\n" + userInput;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        StringBuilder sb = new StringBuilder();
        try {
            String paiMd = ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
            if (paiMd != null && !paiMd.isBlank()) {
                sb.append(paiMd);
            }
        } catch (Exception e) {
            log.warn("Failed to load BETTER.md project memory", e);
        }
        // 拼接 Agent 维护的记忆摘要（前 50 条 / 10KB 硬上限）
        try {
            String agentMemory = toolRegistry.getAgentMemorySummary(50, 10_240);
            if (agentMemory != null && !agentMemory.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(agentMemory);
            }
        } catch (Exception e) {
            log.warn("Failed to load agent memory summary", e);
        }
        // 会话记事本摘要（structured note-taking）：压缩后仍可 notebook_read 读回全文
        try {
            String notebook = sessionNotebook.formatSummary(12, 2_000);
            if (notebook != null && !notebook.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(notebook);
            }
        } catch (Exception e) {
            log.warn("Failed to format session notebook summary", e);
        }
        return sb.toString();
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    /**
     * 路由命中 Custom SubAgent 后，把本轮 user/assistant 写入主会话，保持对话连续。
     */
    public void recordExternalTurn(String userInput, String assistantReply) {
        recordExternalTurn(userInput, assistantReply, null);
    }

    /**
     * 路由/硬指定命中后写入主会话；{@code viaSubagent} 非空时在 assistant 内容前标注来源（对标文档可观测）。
     */
    public void recordExternalTurn(String userInput, String assistantReply, String viaSubagent) {
        if (userInput != null && !userInput.isBlank()) {
            memoryManager.addUserMessage(userInput);
            conversationHistory.add(LlmClient.Message.user(userInput));
        }
        String reply = assistantReply == null ? "" : assistantReply;
        if (viaSubagent != null && !viaSubagent.isBlank()) {
            String tag = "[via:" + viaSubagent.trim() + "]\n";
            if (!reply.startsWith(tag) && !reply.startsWith("[via:")) {
                reply = tag + reply;
            }
        }
        memoryManager.addAssistantMessage(reply);
        conversationHistory.add(LlmClient.Message.assistant(reply));
        if (sessionMessageIndexer != null) {
            try {
                sessionMessageIndexer.indexIncremental(new ArrayList<>(conversationHistory));
            } catch (Exception e) {
                log.debug("异步索引会话消息失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 主会话近期 transcript 摘录，供路由直达子 Agent 延续上下文（跳过 system）。
     */
    public String buildTranscriptExcerpt(int maxMessages, int maxChars) {
        List<LlmClient.Message> msgs = recentDialogueMessages(maxMessages);
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : msgs) {
            String role = m.role() == null ? "?" : m.role();
            String content = m.content() == null ? "" : m.content().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > 400) {
                content = content.substring(0, 400) + "...";
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(role).append(": ").append(content);
            if (sb.length() >= maxChars) {
                break;
            }
        }
        if (sb.length() > maxChars) {
            return sb.substring(0, Math.max(0, maxChars)) + "...";
        }
        return sb.toString();
    }

    /** 供 Custom SubAgent 路由模式 seed 的近期对话（user/assistant，无 tool）。 */
    public List<LlmClient.Message> recentDialogueMessages(int maxMessages) {
        int limit = Math.max(1, maxMessages);
        List<LlmClient.Message> msgs = new ArrayList<>();
        for (LlmClient.Message m : conversationHistory) {
            if (m == null) {
                continue;
            }
            String role = m.role() == null ? "" : m.role().toLowerCase();
            if ("system".equals(role) || "tool".equals(role)) {
                continue;
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                continue;
            }
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            msgs.add(m);
        }
        if (msgs.size() > limit) {
            return new ArrayList<>(msgs.subList(msgs.size() - limit, msgs.size()));
        }
        return msgs;
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    private void storeExplicitBrowserMemoryHint(String userInput) {
        List<String> recentTexts = conversationHistory.stream()
                .map(LlmClient.Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
    }

    public String getContextStatus() {
        com.bettercli.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = com.bettercli.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.bettercli.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = com.bettercli.memory.TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            LlmClient.Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = com.bettercli.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.bettercli.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    hitl,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls, int iteration) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = toolRegistry.executeTools(invocations);
        if (customSubAgentRunner != null) {
            long pending = results.stream()
                    .filter(r -> CustomSubAgentRunner.parsePendingSessionId(r.result()) != null)
                    .count();
            if (pending > 0) {
                String phase = customSubAgentRunner.activeRunsSummary();
                if (phase == null || phase.isBlank()) {
                    phase = "sa×" + pending;
                }
                try {
                    renderer().updateStatus(currentStatus(phase));
                } catch (Exception e) {
                    log.debug("subagent status update skipped: {}", e.getMessage());
                }
                try {
                    renderer().stream().println(AnsiStyle.subtle(
                            "  ⏳ 等待 " + pending + " 个 Custom SubAgent 完成…"));
                } catch (Exception e) {
                    log.debug("subagent wait hint skipped: {}", e.getMessage());
                }
            }
            results = customSubAgentRunner.materializeAsyncResults(results);
            // 防止占位泄漏进对话
            List<ToolExecutionResult> sanitized = new ArrayList<>(results.size());
            for (ToolExecutionResult r : results) {
                if (CustomSubAgentRunner.parsePendingSessionId(r.result()) != null) {
                    sanitized.add(new ToolExecutionResult(
                            r.id(), r.name(), r.argumentsJson(),
                            "run_subagent 失败: 异步结果未回填",
                            r.elapsedMillis(), false, r.imageParts()));
                } else {
                    sanitized.add(r);
                }
            }
            results = sanitized;
        }
        for (ToolExecutionResult result : results) {
            log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
            emitToolResultSummary(result);
        }
        return results;
    }

    private void emitToolResultSummary(ToolExecutionResult result) {
        if (result == null || result.name() == null) {
            return;
        }
        String summary = switch (result.name()) {
            case "web_search" -> webSearchSummary(result);
            case "web_fetch" -> webFetchSummary(result);
            default -> "";
        };
        if (!summary.isBlank()) {
            renderer().stream().println(AnsiStyle.subtle("  → " + summary));
        }
    }

    private String webSearchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(result.argumentsJson(), "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private String webFetchSummary(ToolExecutionResult result) {
        String text = result.result() == null ? "" : result.result();
        boolean stepSearch = isStepSearchResult(text);
        String url = extractJsonArg(result.argumentsJson(), "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (text.startsWith("抓取失败") || text.startsWith("❌")) {
            return verb + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
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

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (!renderer().rendersReasoning() || normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private boolean rendersReasoning() {
            return renderer == null || renderer.rendersReasoning();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking(com.bettercli.i18n.UiText.thinkingLabel());
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningRenderer = newMarkdownRenderer();
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningRenderer.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = newMarkdownRenderer();
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentRenderer = newMarkdownRenderer();
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            if (renderer != null) {
                renderer.appendAssistantContentDelta(delta);
            }
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
            }
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            TerminalMarkdownRenderer renderer = newMarkdownRenderer();
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private TerminalMarkdownRenderer newMarkdownRenderer() {
            if (renderer != null) {
                return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
            }
            return new TerminalMarkdownRenderer(out());
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            printThinkingQuoteIfNeeded();
        }

        private void printThinkingQuoteIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            String reasoning = visibleReasoning.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            if (reasoning.isEmpty()) {
                return;
            }
            out().println(AnsiStyle.thinking(com.bettercli.i18n.UiText.thinkingDots()));
            for (String line : reasoning.split("\\R+")) {
                String normalized = line.replaceAll("\\s+", " ").trim();
                if (!normalized.isEmpty()) {
                    out().println(AnsiStyle.subtle("│ " + normalized));
                }
            }
            out().println();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                if (!rendersReasoning()) {
                    return;
                }
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
