package com.bettercli.memory;

import com.bettercli.context.ContextProfile;

/**
 * 单次压缩评估/执行所需的窗口与阈值参数（对标 1024 溢出双条件）。
 */
public record CompactConfig(
        int contextWindow,
        int maxOutputTokens,
        int compactionBufferTokens,
        int minMessageBodyTokens,
        int recentUserMessageBudgetTokens,
        int summaryMaxTokens,
        Integer lastKnownTotalTokens,
        int toolsSchemaTokens
) {
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    public static final int DEFAULT_COMPACTION_BUFFER_TOKENS = 20_000;
    public static final int DEFAULT_MIN_MESSAGE_BODY_TOKENS = 20_000;
    public static final int DEFAULT_RECENT_USER_BUDGET_TOKENS = 5_000;
    /** 对标 1024 compaction.summaryMaxTokens，默认 6000（文档建议 4000~8000） */
    public static final int DEFAULT_SUMMARY_MAX_TOKENS = 6_000;

    public CompactConfig {
        toolsSchemaTokens = Math.max(0, toolsSchemaTokens);
    }

    public static CompactConfig from(ContextProfile profile) {
        return from(profile, null, 0);
    }

    public static CompactConfig from(ContextProfile profile, Integer lastKnownTotalTokens) {
        return from(profile, lastKnownTotalTokens, 0);
    }

    public static CompactConfig from(ContextProfile profile,
                                     Integer lastKnownTotalTokens,
                                     int toolsSchemaTokens) {
        int window = profile == null ? 128_000 : profile.maxContextWindow();
        return new CompactConfig(
                window,
                DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_COMPACTION_BUFFER_TOKENS,
                DEFAULT_MIN_MESSAGE_BODY_TOKENS,
                DEFAULT_RECENT_USER_BUDGET_TOKENS,
                readSummaryMaxTokens(),
                lastKnownTotalTokens,
                toolsSchemaTokens
        );
    }

    /** 可用上限 = window - 压缩缓冲 - 最大输出预留 */
    public int availableLimitTokens() {
        return Math.max(1_000, contextWindow - compactionBufferTokens - maxOutputTokens);
    }

    private static int readSummaryMaxTokens() {
        String prop = System.getProperty("bettercli.compaction.summary.max.tokens");
        String env = System.getenv("BETTERCLI_COMPACTION_SUMMARY_MAX_TOKENS");
        String raw = prop != null ? prop : env;
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SUMMARY_MAX_TOKENS;
        }
        try {
            return Math.max(500, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_SUMMARY_MAX_TOKENS;
        }
    }
}
