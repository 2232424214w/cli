package com.bettercli.subagent;

import com.bettercli.agent.AgentMessage;
import com.bettercli.agent.SubAgent;
import com.bettercli.agent.SubAgentResult;
import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmClientFactory;
import com.bettercli.runtime.CancellationContext;
import com.bettercli.skill.SkillContextBuffer;
import com.bettercli.skill.SkillRegistry;
import com.bettercli.tool.ToolRegistry;
import com.bettercli.tool.ToolRegistry.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按 Custom SubAgent 定义在隔离上下文中跑一轮 ReAct。
 *
 * <p>主 Agent 委托：{@link #startAsync} 立即返回占位，后台线程池执行，
 * {@link #materializeAsyncResults} 在工具批次结束后回填真实结果（可同轮并行多个）。
 * 路由直达：{@link #runDirect} 同步执行并返回最终答复。
 *
 * <p>禁止嵌套（ThreadLocal）；支持超时与 {@link CancellationContext}；执行期 ThreadLocal 注入
 * skills 白名单与 MEMORY 写回路径。
 */
public final class CustomSubAgentRunner {

    public static final String PENDING_PREFIX = "CUSTOM_SUBAGENT_PENDING:";

    private static final Logger log = LoggerFactory.getLogger(CustomSubAgentRunner.class);
    private static final ThreadLocal<Boolean> IN_CUSTOM = ThreadLocal.withInitial(() -> false);

    private final CustomSubAgentRegistry registry;
    private final Map<String, CustomSubAgentRunStatus> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, PendingRun> pendingBySession = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "custom-subagent");
        t.setDaemon(true);
        return t;
    });

    public CustomSubAgentRunner(CustomSubAgentRegistry registry) {
        this.registry = registry;
    }

    public CustomSubAgentRegistry registry() {
        return registry;
    }

    public List<CustomSubAgentRunStatus> activeRuns() {
        return new ArrayList<>(activeRuns.values());
    }

    /** 当前是否在 Custom SubAgent 执行线程内（防递归）。 */
    public static boolean isInCustomSubAgent() {
        return Boolean.TRUE.equals(IN_CUSTOM.get());
    }

    /**
     * 主 Agent 委托入口：立即返回占位，后台执行；调用方须在工具批次后
     * {@link #materializeAsyncResults}。
     */
    public String startAsync(String name, String task, LlmClient parentClient, ToolRegistry toolRegistry,
                             PrintStream progressOut, String parentConversationId) {
        if (isInCustomSubAgent()) {
            return "run_subagent 失败: 不可嵌套调用（Custom SubAgent 执行中）";
        }
        if (registry == null) {
            return "run_subagent 失败: Custom SubAgent 注册表未初始化";
        }
        CustomSubAgentDefinition def = registry.find(name);
        if (def == null) {
            return "run_subagent 失败: 未找到子 Agent \"" + name + "\"\n" + availableList();
        }
        return startAsync(def, task, parentClient, toolRegistry, progressOut, parentConversationId);
    }

    public String startAsync(CustomSubAgentDefinition def, String task,
                             LlmClient parentClient, ToolRegistry toolRegistry,
                             PrintStream progressOut, String parentConversationId) {
        if (isInCustomSubAgent()) {
            return "run_subagent 失败: 不可嵌套调用（Custom SubAgent 执行中）";
        }
        String prepError = validate(def, task, parentClient, toolRegistry);
        if (prepError != null) {
            return prepError;
        }

        String childSessionId = newSessionId(def.name());
        String parentId = normalizeParentId(parentConversationId);
        String taskPreview = preview(task.trim(), 80);
        int timeoutSec = def.resolveTimeoutSeconds();

        activeRuns.put(childSessionId, new CustomSubAgentRunStatus(
                def.name(), childSessionId, parentId, Instant.now(), taskPreview));
        CustomSubAgentAudit.record("SUBAGENT_STARTED", def.name(), childSessionId, parentId, taskPreview);

        Future<String> future = pool.submit(() -> executeIsolated(
                def, task.trim(), parentClient, toolRegistry, progressOut,
                childSessionId, parentId, null, null, false));

        pendingBySession.put(childSessionId, new PendingRun(future, timeoutSec, def.name()));
        return placeholder(childSessionId, def.name(), parentId, timeoutSec);
    }

    /**
     * 将工具结果中的异步占位替换为真实子 Agent 输出（按 session 等待，保持结果顺序）。
     */
    public List<ToolExecutionResult> materializeAsyncResults(List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        List<ToolExecutionResult> out = new ArrayList<>(results.size());
        for (ToolExecutionResult r : results) {
            String text = r.result();
            String sessionId = parsePendingSessionId(text);
            if (sessionId == null) {
                out.add(r);
                continue;
            }
            PendingRun pending = pendingBySession.remove(sessionId);
            if (pending == null) {
                out.add(new ToolExecutionResult(
                        r.id(), r.name(), r.argumentsJson(),
                        "run_subagent 失败: 找不到异步任务 session=" + sessionId,
                        r.elapsedMillis(), false, r.imageParts()));
                continue;
            }
            String materialized = awaitPending(pending, sessionId);
            out.add(new ToolExecutionResult(
                    r.id(), r.name(), r.argumentsJson(),
                    materialized, r.elapsedMillis(), false, r.imageParts()));
        }
        return out;
    }

    /**
     * 路由直达：以子 Agent 身份同步响应；可 seed 主会话 messages，也可附带 transcript 文本兜底。
     */
    public String runDirect(String name, String userMessage, LlmClient parentClient,
                            ToolRegistry toolRegistry, PrintStream progressOut,
                            String parentConversationId,
                            List<LlmClient.Message> parentHistory,
                            String parentTranscript) {
        if (registry == null) {
            return "❌ Custom SubAgent 注册表未初始化";
        }
        CustomSubAgentDefinition def = registry.find(name);
        if (def == null) {
            return "❌ 未找到子 Agent \"" + name + "\"\n" + availableList();
        }
        return runDirect(def, userMessage, parentClient, toolRegistry, progressOut,
                parentConversationId, parentHistory, parentTranscript);
    }

    /** 兼容旧调用：无 history seed，仅 transcript 文本。 */
    public String runDirect(String name, String userMessage, LlmClient parentClient,
                            ToolRegistry toolRegistry, PrintStream progressOut,
                            String parentConversationId, String parentTranscript) {
        return runDirect(name, userMessage, parentClient, toolRegistry, progressOut,
                parentConversationId, null, parentTranscript);
    }

    public String runDirect(CustomSubAgentDefinition def, String userMessage,
                            LlmClient parentClient, ToolRegistry toolRegistry,
                            PrintStream progressOut, String parentConversationId,
                            List<LlmClient.Message> parentHistory,
                            String parentTranscript) {
        String prepError = validate(def, userMessage, parentClient, toolRegistry);
        if (prepError != null) {
            return "❌ " + prepError.replace("run_subagent 失败: ", "");
        }
        String childSessionId = newSessionId(def.name());
        String parentId = normalizeParentId(parentConversationId);
        String taskPreview = preview(userMessage.trim(), 80);
        int timeoutSec = def.resolveTimeoutSeconds();

        activeRuns.put(childSessionId, new CustomSubAgentRunStatus(
                def.name(), childSessionId, parentId, Instant.now(), taskPreview));
        CustomSubAgentAudit.record("SUBAGENT_ROUTED_RUN", def.name(), childSessionId, parentId, taskPreview);

        if (progressOut != null) {
            progressOut.println("🧭 路由命中 Custom SubAgent [" + def.name() + "]，跳过主 Agent");
        }

        Future<String> future = pool.submit(() -> executeIsolated(
                def, userMessage.trim(), parentClient, toolRegistry, progressOut,
                childSessionId, parentId, parentHistory, parentTranscript, true));
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            CustomSubAgentAudit.record("SUBAGENT_TIMEOUT", def.name(), childSessionId, parentId, null);
            return "❌ Custom SubAgent [" + def.name() + "] 超时（" + timeoutSec + "s） session=" + childSessionId;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            CustomSubAgentAudit.record("SUBAGENT_ERROR", def.name(), childSessionId, parentId, cause.getMessage());
            return "❌ Custom SubAgent 失败: " + cause.getMessage();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return "⏹️ 已取消";
        } finally {
            activeRuns.remove(childSessionId);
        }
    }

    public String runDirect(CustomSubAgentDefinition def, String userMessage,
                            LlmClient parentClient, ToolRegistry toolRegistry,
                            PrintStream progressOut, String parentConversationId,
                            String parentTranscript) {
        return runDirect(def, userMessage, parentClient, toolRegistry, progressOut,
                parentConversationId, null, parentTranscript);
    }

    /** 兼容旧测试：同步跑完并返回结果（内部仍走线程池）。 */
    public String run(String name, String task, LlmClient parentClient, ToolRegistry toolRegistry) {
        return run(name, task, parentClient, toolRegistry, null, null);
    }

    public String run(String name, String task, LlmClient parentClient, ToolRegistry toolRegistry,
                      PrintStream progressOut, String parentConversationId) {
        String placeholder = startAsync(name, task, parentClient, toolRegistry, progressOut, parentConversationId);
        String sessionId = parsePendingSessionId(placeholder);
        if (sessionId == null) {
            return placeholder; // 已是错误信息
        }
        PendingRun pending = pendingBySession.remove(sessionId);
        if (pending == null) {
            return "run_subagent 失败: 异步任务丢失 session=" + sessionId;
        }
        return awaitPending(pending, sessionId);
    }

    public String run(CustomSubAgentDefinition def, String task,
                      LlmClient parentClient, ToolRegistry toolRegistry) {
        return run(def, task, parentClient, toolRegistry, null, null);
    }

    public String run(CustomSubAgentDefinition def, String task,
                      LlmClient parentClient, ToolRegistry toolRegistry,
                      PrintStream progressOut, String parentConversationId) {
        String placeholder = startAsync(def, task, parentClient, toolRegistry, progressOut, parentConversationId);
        String sessionId = parsePendingSessionId(placeholder);
        if (sessionId == null) {
            return placeholder;
        }
        PendingRun pending = pendingBySession.remove(sessionId);
        if (pending == null) {
            return "run_subagent 失败: 异步任务丢失 session=" + sessionId;
        }
        return awaitPending(pending, sessionId);
    }

    private String executeIsolated(CustomSubAgentDefinition def, String task,
                                   LlmClient parentClient, ToolRegistry toolRegistry,
                                   PrintStream progressOut, String childSessionId,
                                   String parentId,
                                   List<LlmClient.Message> parentHistory,
                                   String parentTranscript,
                                   boolean directResponder) {
        IN_CUSTOM.set(true);
        CustomSubAgentRuntimeContext prevCtx = toolRegistry.getCustomSubAgentContext();
        try {
            CustomSubAgentRuntimeContext ctx = new CustomSubAgentRuntimeContext(
                    def.name(), def.memoryFilePath(), def.skills());
            toolRegistry.setCustomSubAgentContext(ctx);

            LlmClient client = resolveClient(def, parentClient);
            Set<String> tools = def.resolveEffectiveTools(toolRegistry.registeredToolNames());
            Set<String> skillWl = def.skills().isEmpty() ? null : new HashSet<>(def.skills());
            SubAgent sub = SubAgent.forCustom(
                    def.name(),
                    client,
                    toolRegistry,
                    def.composeSystemPromptCore(),
                    def.memoryMd(),
                    tools,
                    def.maxTurns(),
                    skillWl);
            SkillRegistry skills = toolRegistry.getSkillRegistry();
            if (skills != null) {
                sub.setSkillRegistry(skills);
            }
            SkillContextBuffer buffer = toolRegistry.getSkillContextBuffer();
            if (buffer != null) {
                sub.setSkillContextBuffer(buffer);
            }

            // 路由直达：真正 seed 主会话 history（优先于 transcript 文本塞进 task）
            if (directResponder && parentHistory != null && !parentHistory.isEmpty()) {
                sub.seedParentHistory(parentHistory, 12);
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream out = TeePrintStream.of(progressOut, buf);
            out.println("🧩 Custom SubAgent [" + def.name() + "] session=" + childSessionId
                    + " parent=" + parentId + " timeout=" + def.resolveTimeoutSeconds() + "s"
                    + (directResponder ? " mode=routed" : " mode=delegate"));
            log.info("Custom SubAgent start name={} childSessionId={} parentConversationId={} direct={}",
                    def.name(), childSessionId, parentId, directResponder);

            String taskPayload = task;
            // history 未 seed 时仍用 transcript 文本兜底
            boolean seeded = directResponder && parentHistory != null && !parentHistory.isEmpty();
            if (!seeded && parentTranscript != null && !parentTranscript.isBlank()) {
                taskPayload = "【主会话近期上下文】\n" + parentTranscript.trim()
                        + "\n\n【当前用户消息】\n" + task;
            }

            AgentMessage result = sub.execute(AgentMessage.task("orchestrator", taskPayload), out);
            if (CancellationContext.isCancelled()) {
                CustomSubAgentAudit.record("SUBAGENT_CANCELLED", def.name(), childSessionId, parentId, null);
                return directResponder
                        ? "⏹️ 已取消"
                        : "run_subagent 失败: 用户取消 (session=" + childSessionId + ")";
            }

            String content = result.content() == null ? "" : result.content().trim();
            if (directResponder) {
                if (result.type() == AgentMessage.Type.ERROR) {
                    CustomSubAgentAudit.record("SUBAGENT_ERROR", def.name(), childSessionId, parentId, content);
                    return "❌ [" + def.name() + "] " + content;
                }
                CustomSubAgentAudit.record("SUBAGENT_DONE", def.name(), childSessionId, parentId, "OK");
                log.info("Custom SubAgent done name={} childSessionId={} status=OK direct=true",
                        def.name(), childSessionId);
                return content;
            }

            SubAgentResult envelope = sub.lastRunResult();
            StringBuilder sb = new StringBuilder();
            sb.append("【Custom SubAgent: ").append(def.name()).append("】\n");
            sb.append("session: ").append(childSessionId).append('\n');
            sb.append("parent_conversation_id: ").append(parentId).append('\n');
            if (result.type() == AgentMessage.Type.ERROR) {
                sb.append("状态: ERROR\n");
                sb.append(content);
                CustomSubAgentAudit.record("SUBAGENT_ERROR", def.name(), childSessionId, parentId, content);
            } else {
                sb.append("状态: OK\n");
                if (envelope != null) {
                    sb.append(envelope.oneLineSummary()).append('\n');
                }
                sb.append(content);
                CustomSubAgentAudit.record("SUBAGENT_DONE", def.name(), childSessionId, parentId, "OK");
            }
            String logs = buf.toString(StandardCharsets.UTF_8);
            if (logs != null && !logs.isBlank() && logs.length() < 2000) {
                sb.append("\n\n--- 执行摘录 ---\n").append(logs.trim());
            }
            log.info("Custom SubAgent done name={} childSessionId={} status={}",
                    def.name(), childSessionId, result.type());
            return sb.toString();
        } catch (Exception e) {
            log.error("Custom SubAgent [{}] failed session={}", def.name(), childSessionId, e);
            CustomSubAgentAudit.record("SUBAGENT_ERROR", def.name(), childSessionId, parentId, e.getMessage());
            return directResponder
                    ? "❌ Custom SubAgent 失败: " + e.getMessage()
                    : "run_subagent 失败: " + e.getMessage() + " (session=" + childSessionId + ")";
        } finally {
            toolRegistry.setCustomSubAgentContext(prevCtx);
            activeRuns.remove(childSessionId);
            IN_CUSTOM.set(false);
            IN_CUSTOM.remove();
        }
    }

    /** 状态栏摘要：运行中的委托数量与名称。 */
    public String activeRunsSummary() {
        List<CustomSubAgentRunStatus> runs = activeRuns();
        if (runs.isEmpty()) {
            return "";
        }
        if (runs.size() == 1) {
            return "sa:" + runs.get(0).agentName();
        }
        return "sa×" + runs.size();
    }

    public int pendingCount() {
        return pendingBySession.size();
    }

    private String awaitPending(PendingRun pending, String sessionId) {
        try {
            return pending.future().get(pending.timeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            pending.future().cancel(true);
            activeRuns.remove(sessionId);
            CustomSubAgentAudit.record("SUBAGENT_TIMEOUT", pending.name(), sessionId, null, null);
            return "run_subagent 失败: 超时（" + pending.timeoutSec() + "s），已中断子 Agent ["
                    + pending.name() + "] session=" + sessionId;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            return "run_subagent 失败: " + cause.getMessage() + " (session=" + sessionId + ")";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pending.future().cancel(true);
            return "run_subagent 失败: 用户取消 (session=" + sessionId + ")";
        }
    }

    private String validate(CustomSubAgentDefinition def, String task,
                            LlmClient parentClient, ToolRegistry toolRegistry) {
        if (def == null) {
            return "run_subagent 失败: 定义不能为空";
        }
        if (task == null || task.isBlank()) {
            return "run_subagent 失败: task 不能为空";
        }
        if (parentClient == null || toolRegistry == null) {
            return "run_subagent 失败: 缺少 LLM 或 ToolRegistry";
        }
        if (CancellationContext.isCancelled()) {
            return "run_subagent 失败: 用户取消";
        }
        return null;
    }

    private LlmClient resolveClient(CustomSubAgentDefinition def, LlmClient parentClient) {
        if (def.model() == null || def.model().isBlank()) {
            return parentClient;
        }
        try {
            LlmClient created = LlmClientFactory.create(def.model().trim(), BetterCliConfig.load());
            if (created != null) {
                return created;
            }
            log.warn("Custom SubAgent [{}] model={} 无法创建，回退主模型", def.name(), def.model());
        } catch (Exception e) {
            log.warn("Custom SubAgent [{}] model resolve failed: {}", def.name(), e.getMessage());
        }
        return parentClient;
    }

    private String availableList() {
        if (registry == null || registry.all().isEmpty()) {
            return "当前未加载任何 Custom SubAgent（请在 ~/.bettercli/agents/ 或 .bettercli/agents/ 下放置 AGENT.md）";
        }
        StringBuilder sb = new StringBuilder("可用子 Agent：\n");
        for (CustomSubAgentDefinition d : registry.all()) {
            sb.append("- ").append(d.name());
            if (d.description() != null && !d.description().isBlank()) {
                sb.append("：").append(d.description().trim());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    static String placeholder(String sessionId, String name, String parentId, int timeoutSec) {
        return PENDING_PREFIX + sessionId + "\n"
                + "⏳ Custom SubAgent [" + name + "] 已启动（异步），后台执行中…\n"
                + "session: " + sessionId + "\n"
                + "parent_conversation_id: " + parentId + "\n"
                + "timeout: " + timeoutSec + "s\n"
                + "主 Agent 本轮其它工具可并行；本批次结束后将自动回填结果。";
    }

    public static String parsePendingSessionId(String text) {
        if (text == null || !text.startsWith(PENDING_PREFIX)) {
            return null;
        }
        String rest = text.substring(PENDING_PREFIX.length());
        int nl = rest.indexOf('\n');
        String id = nl < 0 ? rest.trim() : rest.substring(0, nl).trim();
        return id.isEmpty() ? null : id;
    }

    private static String newSessionId(String name) {
        return "sub_" + name + "_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String normalizeParentId(String parentConversationId) {
        return parentConversationId == null || parentConversationId.isBlank()
                ? "(none)" : parentConversationId.trim();
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private record PendingRun(Future<String> future, int timeoutSec, String name) {
    }
}
