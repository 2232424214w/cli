package com.bettercli.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 超长会话文本的 keyword-centered 窗口截断（对标 1024 session_search 阶段③）。
 * 以命中关键词为中心取前 25% / 后 75%，避免只截头部丢掉相关上下文。
 */
public final class SessionSearchWindow {

    public static final int DEFAULT_MAX_CHARS = 100_000;

    private SessionSearchWindow() {
    }

    /**
     * 将会话格式化为可读文本；超过 maxChars 时按 query 居中截断。
     */
    public static String formatWithWindow(List<SessionMessage> messages, String query, int maxChars) {
        if (messages == null || messages.isEmpty()) {
            return "(空会话)";
        }
        int budget = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        String full = formatAll(messages);
        if (full.length() <= budget) {
            return full;
        }
        return centerTruncate(full, query, budget);
    }

    static String formatAll(List<SessionMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (SessionMessage msg : messages) {
            if (msg == null) {
                continue;
            }
            String label = switch (msg.getRole() == null ? "" : msg.getRole()) {
                case "user" -> "[USER]";
                case "assistant" -> "[ASSISTANT]";
                case "tool" -> "[TOOL]";
                case "system" -> "[SYSTEM]";
                default -> "[" + (msg.getRole() == null ? "?" : msg.getRole().toUpperCase(Locale.ROOT)) + "]";
            };
            String text = msg.getContent() == null ? "" : msg.getContent();
            sb.append(label).append(": ").append(text).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 前 25% 窗口在命中点之前，后 75% 在命中点之后（含命中点）。
     */
    static String centerTruncate(String text, String query, int maxChars) {
        if (text == null || text.isEmpty() || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int hit = findHitIndex(text, query);
        if (hit < 0) {
            hit = text.length() / 2;
        }
        int before = (int) (maxChars * 0.25);
        int after = maxChars - before;
        int start = Math.max(0, hit - before);
        int end = Math.min(text.length(), start + maxChars);
        if (end - start < maxChars) {
            start = Math.max(0, end - maxChars);
        }
        // 微调：保证 hit 落在窗口内
        if (hit < start) {
            start = Math.max(0, hit);
            end = Math.min(text.length(), start + maxChars);
        } else if (hit >= end) {
            end = Math.min(text.length(), hit + after);
            start = Math.max(0, end - maxChars);
        }
        String slice = text.substring(start, end);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("...(前文已截断)\n");
        }
        sb.append(slice);
        if (end < text.length()) {
            sb.append("\n...(后文已截断)");
        }
        return sb.toString();
    }

    static int findHitIndex(String text, String query) {
        if (query == null || query.isBlank() || text == null) {
            return -1;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : tokenize(query)) {
            int idx = lower.indexOf(token.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                return idx;
            }
        }
        // 整句查询
        return lower.indexOf(query.trim().toLowerCase(Locale.ROOT));
    }

    private static List<String> tokenize(String query) {
        List<String> out = new ArrayList<>();
        for (String part : query.trim().split("\\s+")) {
            if (part.length() >= 2) {
                out.add(part);
            }
        }
        return out;
    }
}
