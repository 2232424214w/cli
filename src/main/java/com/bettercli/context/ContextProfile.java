package com.bettercli.context;

import com.bettercli.llm.LlmClient;

/**
 * 上下文策略配置。
 *
 * **设计原则**：没有"长 / 短 / 平衡"模式分档。所有参数都是 maxContextWindow 的简单函数，
 * 全模型走同一套行为，只是 window 大小不同导致触发时机和容量不同。
 *
 * 主轨压缩（对标 1024）：
 * - 可用上限 = window − 压缩缓冲(20k) − 最大输出预留(8k)
 * - 另需消息体（不含 system）≥ 有效压缩阈值(20k) 才触发，避免无效压缩
 *
 * 按 window 派生：
 * - 短期记忆预算 = window × 0.45
 * - 注入到 system prompt 的相关记忆 token 上限 = window × 0.005，封顶 5000
 * - MCP resource 索引注入：window ≥ 32k 才有意义（再小就挤）
 */
public record ContextProfile(
        int maxContextWindow,
        int agentTokenBudget,
        double compressionTriggerRatio,
        int shortTermMemoryBudget,
        int memoryContextTokens,
        boolean mcpResourceIndexEnabled,
        boolean promptCachingSupported,
        String promptCacheMode
) {
    /** 对标 1024 compaction buffer */
    public static final int COMPACTION_BUFFER_TOKENS = 20_000;
    /** 对标 1024：消息体有效压缩阈值 */
    public static final int MIN_MESSAGE_BODY_TOKENS = 20_000;
    /** 最大输出预留（计入可用上限） */
    public static final int MAX_OUTPUT_RESERVE_TOKENS = 8_192;
    /** @deprecated 使用 {@link #COMPACTION_BUFFER_TOKENS} */
    @Deprecated
    public static final int MAX_SUMMARY_OUTPUT_RESERVE_TOKENS = COMPACTION_BUFFER_TOKENS;
    /** @deprecated 1024 对齐后由 {@link #MAX_OUTPUT_RESERVE_TOKENS} 取代 */
    @Deprecated
    public static final int AUTOCOMPACT_BUFFER_TOKENS = MAX_OUTPUT_RESERVE_TOKENS;
    public static final double MIN_COMPRESSION_TRIGGER_RATIO = 0.50;
    private static final int MIN_WINDOW = 8_000;
    private static final int MCP_RESOURCE_INDEX_MIN_WINDOW = 32_000;

    public static ContextProfile from(LlmClient llmClient) {
        int window = Math.max(MIN_WINDOW, llmClient == null ? 128_000 : llmClient.maxContextWindow());
        return new ContextProfile(
                window,
                agentBudget(window),
                compressionTriggerRatio(window),
                shortTermBudget(window),
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode()
        );
    }

    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget) {
        int window = Math.max(MIN_WINDOW, contextWindow);
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        return new ContextProfile(
                window,
                agentBudget(window),
                compressionTriggerRatio(window),
                shortTerm,
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none"
        );
    }

    /**
     * 主轨可用上限（对标 1024：window − buffer − maxOutput）。
     * 总 token 超过此值且消息体超过 {@link #minMessageBodyTokens()} 时触发压缩。
     */
    public int compressionTriggerTokens() {
        return availableLimitTokens(maxContextWindow);
    }

    public int minMessageBodyTokens() {
        return MIN_MESSAGE_BODY_TOKENS;
    }

    public int compactionBufferTokens() {
        return COMPACTION_BUFFER_TOKENS;
    }

    public int maxOutputReserveTokens() {
        return MAX_OUTPUT_RESERVE_TOKENS;
    }

    public String summary() {
        return "window: " + maxContextWindow
                + " | 可用上限: " + compressionTriggerTokens() + " tokens"
                + " (buffer=" + COMPACTION_BUFFER_TOKENS + ", maxOut=" + MAX_OUTPUT_RESERVE_TOKENS + ")"
                + " | 消息体阈值: " + MIN_MESSAGE_BODY_TOKENS
                + " | 短期记忆预算: " + shortTermMemoryBudget
                + " | MCP resource 索引: " + (mcpResourceIndexEnabled ? "on" : "off")
                + " | prompt cache: " + promptCacheMode;
    }

    private static int agentBudget(int window) {
        // Agent 单次 run 的 token 上限（input + output 累计），保 20% 余量给响应突发
        return Math.max(4_000, (int) Math.floor(window * 0.8));
    }

    private static int shortTermBudget(int window) {
        return Math.max(4_000, (int) Math.floor(window * 0.45));
    }

    private static int memoryContextTokens(int window) {
        return Math.max(500, Math.min(5_000, window / 200));
    }

    private static double compressionTriggerRatio(int window) {
        return Math.max(MIN_COMPRESSION_TRIGGER_RATIO,
                Math.min(0.99, availableLimitTokens(window) / (double) window));
    }

    private static int availableLimitTokens(int window) {
        int safeWindow = Math.max(MIN_WINDOW, window);
        int trigger = safeWindow - COMPACTION_BUFFER_TOKENS - MAX_OUTPUT_RESERVE_TOKENS;
        return Math.max(1_000, Math.min(safeWindow - 1, trigger));
    }
}
