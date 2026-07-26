package com.bettercli.subagent;

/**
 * Custom SubAgent 后台并发上限（按 parent conversationId）。
 *
 * <p>{@code BETTERCLI_SUBAGENT_BG_MAX_CONCURRENT} / {@code -Dbettercli.subagent.bg.max.concurrent}，
 * 默认 3；{@code 0} 或负数表示不限制。
 */
public final class SubAgentBgLimits {

    public static final int DEFAULT_MAX_CONCURRENT = 3;

    private SubAgentBgLimits() {
    }

    public static int maxConcurrentBackground() {
        String raw = System.getProperty("bettercli.subagent.bg.max.concurrent");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("BETTERCLI_SUBAGENT_BG_MAX_CONCURRENT");
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_CONCURRENT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_CONCURRENT;
        }
    }
}
