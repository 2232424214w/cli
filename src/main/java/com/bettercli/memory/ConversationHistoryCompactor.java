package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * ReAct 主循环 {@code conversationHistory} 的 Context Checkpoint Compaction（对标 1024）。
 *
 * <p>与 {@link ContextCompressor} 的区别：后者压短期记忆条目；本类压真正发给 LLM 的消息列表。
 *
 * <p>核心流程：
 * <ol>
 *   <li>双条件溢出检测（总 token 超可用上限 + 消息体超有效阈值）</li>
 *   <li>按 Pre-Turn / Mid-Turn 切割待压缩段与保留段</li>
 *   <li>渐进裁剪 + 同主模型生成交接摘要</li>
 *   <li>构建全 user-role 检查点（近期真实用户消息 + AI 摘要）并原地替换</li>
 * </ol>
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;
    private static final int MIN_SUMMARY_INPUT_CHARS = 8_000;
    private static final double PROGRESSIVE_FLOOR_RATIO = 0.25;

    /** 摘要前缀：中性交接（CLI 多为同一主模型自压自接，不暗示跨模型）。 */
    public static final String SUMMARY_PREFIX =
            "[上下文检查点] 以下是此前对话的压缩摘要，请在此基础上继续。\n\n";

    public static final String SUMMARY_MARKER = "[上下文检查点]";
    public static final String LEGACY_SUMMARY_MARKER = "[已压缩的历史对话摘要]";
    public static final String HARD_TRUNCATE_MARKER = "[历史对话已硬截断]";

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成一份任务交接摘要。必须按以下小节输出（无内容写「无」），不要加其它前缀：

            【任务目标】用户最初要完成什么（原话意图，勿扩写）
            【关键约束】必须遵守的限制、偏好、禁止项
            【当前进展】已完成事项与关键决策
            【未完成待办】后续步骤（按优先级）
            【关键数据】继续工作必需的路径、标识、配置或参考（无则写无）

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            控制在约 %d tokens 以内（约 %d 汉字）。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private Consumer<CompactCheckpoint> checkpointListener;
    private int activeSummaryMaxTokens = CompactConfig.DEFAULT_SUMMARY_MAX_TOKENS;
    private int activeSummaryInputCharLimit = MAX_SUMMARY_INPUT_CHARS;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 压缩成功后回调（用于写 session.jsonl 的 compacted 行）；失败不影响内存侧压缩。 */
    public void setCheckpointListener(Consumer<CompactCheckpoint> checkpointListener) {
        this.checkpointListener = checkpointListener;
    }

    /**
     * 按 1024 双条件判断是否需要压缩。
     *
     * @param history           完整消息列表（可含 system）
     * @param config            窗口与阈值
     * @param force             true 时跳过阈值（MANUAL / API 兜底）
     */
    public boolean needsCompaction(List<LlmClient.Message> history, CompactConfig config, boolean force) {
        if (force) {
            return history != null && !history.isEmpty();
        }
        if (history == null || history.isEmpty() || config == null) {
            return false;
        }
        int systemEnd = systemEnd(history);
        // 消息体不含 System / Tools Schema（对标 1024 条件二）
        int bodyTokens = TokenBudget.estimateMessagesTokens(history.subList(systemEnd, history.size()));
        if (bodyTokens < config.minMessageBodyTokens()) {
            return false;
        }
        return resolveTotalTokens(history, config) > config.availableLimitTokens();
    }

    /**
     * 解析用于条件一的总 token。
     * <p>估算 = 消息 + Tools Schema；若有 API usage 则取 {@code max(usage, 估算)}，
     * 避免 Mid-Turn 工具结果写入后仍沿用工具执行前的过期 usage 而漏触发。
     */
    public int resolveTotalTokens(List<LlmClient.Message> history, CompactConfig config) {
        if (history == null || history.isEmpty() || config == null) {
            return 0;
        }
        int estimated = TokenBudget.estimateMessagesTokens(history) + Math.max(0, config.toolsSchemaTokens());
        if (config.lastKnownTotalTokens() == null) {
            return estimated;
        }
        return Math.max(config.lastKnownTotalTokens(), estimated);
    }

    /**
     * 执行一次检查点压缩。
     *
     * @param history                 原地修改
     * @param trigger                 触发点
     * @param config                  阈值
     * @param currentUserMessageIndex Pre-Turn 切割边界（含 system 的绝对下标）；Mid-Turn 可传 -1
     * @return 是否真正压缩
     */
    public boolean compact(List<LlmClient.Message> history,
                           CompactTrigger trigger,
                           CompactConfig config,
                           int currentUserMessageIndex) {
        if (history == null || history.isEmpty() || config == null || trigger == null) {
            return false;
        }
        boolean force = trigger == CompactTrigger.MANUAL
                || trigger == CompactTrigger.PROMPT_TOO_LONG
                || trigger == CompactTrigger.CONTEXT_WINDOW_EXCEEDED;
        if (!needsCompaction(history, config, force)) {
            return false;
        }
        this.activeSummaryMaxTokens = Math.max(500, config.summaryMaxTokens());
        this.activeSummaryInputCharLimit = summaryInputCharLimit(config);

        int systemEnd = systemEnd(history);
        int splitIdx;
        if (trigger == CompactTrigger.PRE_TURN) {
            splitIdx = currentUserMessageIndex;
            if (splitIdx < systemEnd || splitIdx >= history.size()) {
                // 回退：以最后一个真实用户消息为边界
                splitIdx = findLastRealUserIndex(history, systemEnd);
            }
            if (splitIdx <= systemEnd) {
                log.info("compact skip pre_turn: no compressible history before current message");
                return false;
            }
        } else {
            // Mid-Turn / Manual / API 兜底：全量压缩
            splitIdx = history.size();
            if (splitIdx <= systemEnd) {
                return false;
            }
        }

        List<LlmClient.Message> toCompress = new ArrayList<>(history.subList(systemEnd, splitIdx));
        List<LlmClient.Message> retain = new ArrayList<>(history.subList(splitIdx, history.size()));
        if (toCompress.isEmpty()) {
            return false;
        }

        int beforeTokens = TokenBudget.estimateMessagesTokens(history);

        if (trigger == CompactTrigger.CONTEXT_WINDOW_EXCEEDED && toCompress.size() >= 2) {
            int drop = Math.max(1, toCompress.size() / 2);
            toCompress = new ArrayList<>(toCompress.subList(drop, toCompress.size()));
            log.info("pre-trim oldest {}% messages for context_window_exceeded", 50);
        }

        String summary = summarizeWithProgressiveTrim(toCompress);
        List<LlmClient.Message> checkpoint;
        if (summary == null || summary.isBlank()) {
            log.warn("summary unavailable after progressive trim; hard-truncate checkpoint");
            checkpoint = buildHardTruncateCheckpoint(toCompress, config.recentUserMessageBudgetTokens());
        } else {
            checkpoint = buildCheckpoint(toCompress, summary.trim(), config.recentUserMessageBudgetTokens());
        }

        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.addAll(checkpoint);
        rebuilt.addAll(retain);

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        if (!force && afterTokens >= beforeTokens && rebuilt.size() >= history.size()) {
            log.info("compact aborted: no size reduction ({} -> {} tokens)", beforeTokens, afterTokens);
            return false;
        }

        history.clear();
        history.addAll(rebuilt);

        // 持久化快照必须含 retain（Pre-Turn 下为本轮用户消息），否则 Resume 快进会丢掉
        List<LlmClient.Message> persistedHistory = new ArrayList<>(checkpoint.size() + retain.size());
        persistedHistory.addAll(checkpoint);
        persistedHistory.addAll(retain);
        CompactCheckpoint persisted = new CompactCheckpoint(
                trigger,
                SUMMARY_PREFIX + (summary == null || summary.isBlank()
                        ? "（摘要不可用，已硬截断较早历史）"
                        : summary.trim()),
                List.copyOf(persistedHistory)
        );
        notifyCheckpoint(persisted);

        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory trigger=%s tokens %d -> %d, messages -> %d, checkpoint=%d retain=%d",
                trigger, beforeTokens, afterTokens, history.size(), checkpoint.size(), retain.size()));
        return true;
    }

    /**
     * 兼容旧调用：单阈值 + 保留最近 N 个 user 轮次。内部映射为 PRE_TURN（以倒数第 N 个 user 为边界）。
     *
     * @deprecated 使用 {@link #compact(List, CompactTrigger, CompactConfig, int)}
     */
    @Deprecated
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int systemEnd = systemEnd(history);
        List<Integer> userIndices = new ArrayList<>();
        for (int i = systemEnd; i < history.size(); i++) {
            if (isRealUserMessage(history.get(i))) {
                userIndices.add(i);
            }
        }
        int retain = 3;
        if (userIndices.size() <= retain) {
            return false;
        }
        int splitIdx = userIndices.get(userIndices.size() - retain);
        int estimated = TokenBudget.estimateMessagesTokens(history);
        if (estimated < triggerTokens) {
            return false;
        }
        CompactConfig config = new CompactConfig(
                Math.max(triggerTokens + CompactConfig.DEFAULT_COMPACTION_BUFFER_TOKENS
                        + CompactConfig.DEFAULT_MAX_OUTPUT_TOKENS, 128_000),
                CompactConfig.DEFAULT_MAX_OUTPUT_TOKENS,
                CompactConfig.DEFAULT_COMPACTION_BUFFER_TOKENS,
                1, // 旧 API 已用 triggerTokens 判定，此处放宽消息体条件
                CompactConfig.DEFAULT_RECENT_USER_BUDGET_TOKENS,
                CompactConfig.DEFAULT_SUMMARY_MAX_TOKENS,
                estimated,
                0
        );
        return compact(history, CompactTrigger.PRE_TURN, config, splitIdx);
    }

    /** 手动压缩：全量 Mid-Turn 语义，强制执行。 */
    public boolean compactNow(List<LlmClient.Message> history) {
        CompactConfig config = CompactConfig.from(null, TokenBudget.estimateMessagesTokens(history));
        return compact(history, CompactTrigger.MANUAL, config, -1);
    }

    public static boolean looksLikePromptTooLong(Throwable error) {
        if (error == null) {
            return false;
        }
        String msg = error.getMessage();
        if (msg == null) {
            return looksLikePromptTooLong(error.getCause());
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("prompt is too long")
                || lower.contains("prompt_too_long")
                || lower.contains("context_length_exceeded")
                || lower.contains("maximum context length")
                || lower.contains("context window")
                || lower.contains("too many tokens")
                || lower.contains("token limit")
                || looksLikePromptTooLong(error.getCause());
    }

    public static boolean looksLikeContextWindowExceeded(Throwable error) {
        if (error == null) {
            return false;
        }
        String msg = error.getMessage();
        if (msg == null) {
            return looksLikeContextWindowExceeded(error.getCause());
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("model_context_window_exceeded")
                || lower.contains("context_window_exceeded")
                || looksLikeContextWindowExceeded(error.getCause());
    }

    private String summarizeWithProgressiveTrim(List<LlmClient.Message> toCompress) {
        int originalSize = toCompress.size();
        int floor = Math.max(1, (int) Math.floor(originalSize * PROGRESSIVE_FLOOR_RATIO));
        List<LlmClient.Message> working = new ArrayList<>(toCompress);
        while (working.size() >= floor) {
            try {
                String summary = summarize(working);
                if (summary != null && !summary.isBlank()) {
                    toCompress.clear();
                    toCompress.addAll(working);
                    return summary;
                }
            } catch (IOException e) {
                log.warn("summary failed (size={}): {}", working.size(), e.toString());
                if (!looksLikePromptTooLong(e) && !looksLikeContextWindowExceeded(e) && working.size() <= floor) {
                    break;
                }
            }
            if (working.size() <= floor) {
                break;
            }
            int before = working.size();
            dropOldestTurn(working);
            log.info("progressive trim: dropped oldest turn ({} -> {}), remaining={}",
                    before, working.size(), working.size());
        }
        toCompress.clear();
        toCompress.addAll(working);
        return null;
    }

    /**
     * 按「轮次」丢弃最旧内容：从头部删到下一条真实用户消息之前（含首条），
     * 避免单条删除造成 tool_call / tool_result 孤儿对。
     */
    static void dropOldestTurn(List<LlmClient.Message> working) {
        if (working == null || working.isEmpty()) {
            return;
        }
        int end = 1;
        if (isRealUserMessage(working.get(0))) {
            while (end < working.size() && !isRealUserMessage(working.get(end))) {
                end++;
            }
        } else {
            while (end < working.size() && !isRealUserMessage(working.get(end))) {
                end++;
            }
        }
        working.subList(0, Math.min(end, working.size())).clear();
    }

    /** 摘要请求正文上限：随窗口收紧，避免摘要调用本身再撞窗。 */
    static int summaryInputCharLimit(CompactConfig config) {
        if (config == null) {
            return MAX_SUMMARY_INPUT_CHARS;
        }
        int reservedTokens = Math.max(1_000, config.summaryMaxTokens()) + 2_000;
        int availableTokens = Math.max(4_000,
                config.contextWindow() - reservedTokens - Math.max(0, config.compactionBufferTokens() / 4));
        int chars = (int) (availableTokens * 2.5);
        return Math.min(MAX_SUMMARY_INPUT_CHARS, Math.max(MIN_SUMMARY_INPUT_CHARS, chars));
    }

    private List<LlmClient.Message> buildCheckpoint(List<LlmClient.Message> toCompress,
                                                    String summaryBody,
                                                    int recentUserBudget) {
        List<LlmClient.Message> checkpoint = new ArrayList<>();
        checkpoint.addAll(extractRecentRealUserMessages(toCompress, recentUserBudget));
        checkpoint.add(LlmClient.Message.user(SUMMARY_PREFIX + summaryBody));
        return checkpoint;
    }

    private List<LlmClient.Message> buildHardTruncateCheckpoint(List<LlmClient.Message> toCompress,
                                                               int recentUserBudget) {
        List<LlmClient.Message> checkpoint = new ArrayList<>();
        checkpoint.addAll(extractRecentRealUserMessages(toCompress, recentUserBudget));
        checkpoint.add(LlmClient.Message.user(
                HARD_TRUNCATE_MARKER + " 摘要服务不可用，已丢弃较早轮次以保住上下文窗口。"
                        + "如需细节请用 read_file / session_search / notebook_read 按需取回。"));
        return checkpoint;
    }

    /**
     * 分级保留真实用户消息（过滤系统注入与旧摘要，避免摘要叠摘要）：
     * <ol>
     *   <li>任务种子：待压缩段中<strong>最早</strong>一条真实用户消息（任务目标）优先钉住</li>
     *   <li>近期：在剩余预算内从后往前贪心保留</li>
     * </ol>
     */
    List<LlmClient.Message> extractRecentRealUserMessages(List<LlmClient.Message> toCompress, int budgetTokens) {
        List<LlmClient.Message> candidates = new ArrayList<>();
        for (LlmClient.Message msg : toCompress) {
            if (isRealUserMessage(msg)) {
                candidates.add(msg);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        int budget = Math.max(0, budgetTokens);
        LlmClient.Message seed = candidates.get(0);
        int seedTokens = TokenBudget.estimateMessagesTokens(List.of(seed));
        boolean pinSeed = candidates.size() > 1 && seedTokens > 0 && seedTokens <= budget;
        int recentBudget = pinSeed ? Math.max(0, budget - seedTokens) : budget;

        List<LlmClient.Message> recent = greedyKeepFromEnd(candidates, recentBudget, pinSeed ? 0 : -1);
        if (!pinSeed) {
            return recent;
        }
        List<LlmClient.Message> kept = new ArrayList<>();
        kept.add(seed);
        kept.addAll(recent);
        return kept;
    }

    /** 从后往前贪心保留；{@code skipIndex} >=0 时跳过该候选下标（通常为种子）。 */
    private static List<LlmClient.Message> greedyKeepFromEnd(List<LlmClient.Message> candidates,
                                                             int budgetTokens,
                                                             int skipIndex) {
        List<LlmClient.Message> kept = new ArrayList<>();
        int used = 0;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (i == skipIndex) {
                continue;
            }
            LlmClient.Message msg = candidates.get(i);
            int tokens = TokenBudget.estimateMessagesTokens(List.of(msg));
            if (!kept.isEmpty() && used + tokens > budgetTokens) {
                break;
            }
            if (kept.isEmpty() && tokens > budgetTokens) {
                // 单条已超预算：仍保留最近一条，避免检查点完全没有用户原文
                kept.add(0, msg);
                break;
            }
            kept.add(0, msg);
            used += tokens;
        }
        return kept;
    }

    static boolean isRealUserMessage(LlmClient.Message msg) {
        if (msg == null || !"user".equals(msg.role())) {
            return false;
        }
        String content = msg.content() == null ? "" : msg.content();
        String trimmed = content.stripLeading();
        // 系统/运行时注入，不是用户原始输入；也不应进入检查点「近期用户消息」段
        if (isSyntheticUserTurn(content)
                || trimmed.startsWith(SUMMARY_MARKER)
                || trimmed.startsWith(LEGACY_SUMMARY_MARKER)
                || trimmed.startsWith(HARD_TRUNCATE_MARKER)
                || trimmed.startsWith("## 已加载 Skill")
                || trimmed.startsWith("[反思提示]")
                || trimmed.startsWith("[LSP")
                || trimmed.startsWith("<skill>")
                || trimmed.startsWith("【Skill")
                || trimmed.startsWith("[Skill")
                || trimmed.startsWith("用户输入：")
                || trimmed.contains("\n用户输入：\n")
                || trimmed.startsWith("[系统]")
                || trimmed.startsWith("[runtime]")
                || trimmed.startsWith("[时间提醒]")) {
            return false;
        }
        return true;
    }

    private int findLastRealUserIndex(List<LlmClient.Message> history, int systemEnd) {
        for (int i = history.size() - 1; i >= systemEnd; i--) {
            if (isRealUserMessage(history.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int systemEnd(List<LlmClient.Message> history) {
        return !history.isEmpty() && "system".equals(history.get(0).role()) ? 1 : 0;
    }

    private void notifyCheckpoint(CompactCheckpoint checkpoint) {
        if (checkpointListener == null) {
            return;
        }
        try {
            checkpointListener.accept(checkpoint);
        } catch (Exception e) {
            log.warn("checkpoint listener failed (memory compaction kept): {}", e.toString());
        }
    }

    /**
     * 不计入「真实用户轮次」的合成 user 消息：SubAgent 完成通知、bg-react 调度提示。
     * 前缀须与 {@code CustomSubAgentCompletionNotice.RUNTIME_PREFIX} / Agent bg-react prompt 保持一致。
     */
    static boolean isSyntheticUserTurn(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (content.startsWith("[bg-react]")) {
            return true;
        }
        return content.contains("BetterCLI runtime context (internal):")
                && content.contains("[SubAgent 完成通知]");
    }

    /**
     * 真正调 LLM 摘要。包可见以便测试通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > activeSummaryInputCharLimit) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT,
                activeSummaryMaxTokens,
                Math.max(200, activeSummaryMaxTokens * 2 / 3),
                sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，按指定小节输出交接摘要，不输出元描述。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    /** 压缩检查点快照（写入 session 的 compacted 行）。 */
    public record CompactCheckpoint(
            CompactTrigger trigger,
            String summaryText,
            List<LlmClient.Message> replacementHistory
    ) {
    }
}
