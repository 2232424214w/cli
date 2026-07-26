package com.bettercli.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史会话检索结果（按会话聚合）。
 *
 * 五阶段管道的输出：
 * - 一个 SessionSearchResult 代表一个命中的会话
 * - matchedMessages 是该会话中 BM25 命中的消息（已截断为 previewChars）
 * - fullConversation 是该会话的完整消息列表（按时间排序），供 Agent 进一步加载
 *
 * 设计参考：docs/memory-system-design.md §5.3
 */
public class SessionMessageSearchResult {
    private final String conversationId;
    private final String project;
    private final Instant firstMessageAt;
    private final Instant lastMessageAt;
    private final int totalMessages;
    private final double bestBm25Score;       // 该会话最高 BM25 分（FTS5 越小越相关，这里已归一化为越大越相关）
    private final List<MatchedMessage> matchedMessages;
    private final List<SessionMessage> fullConversation;

    public SessionMessageSearchResult(String conversationId, String project,
                                       Instant firstMessageAt, Instant lastMessageAt,
                                       int totalMessages, double bestBm25Score,
                                       List<MatchedMessage> matchedMessages,
                                       List<SessionMessage> fullConversation) {
        this.conversationId = conversationId;
        this.project = project;
        this.firstMessageAt = firstMessageAt;
        this.lastMessageAt = lastMessageAt;
        this.totalMessages = totalMessages;
        this.bestBm25Score = bestBm25Score;
        this.matchedMessages = matchedMessages == null ? List.of() : List.copyOf(matchedMessages);
        this.fullConversation = fullConversation == null ? List.of() : List.copyOf(fullConversation);
    }

    public String getConversationId() { return conversationId; }
    public String getProject() { return project; }
    public Instant getFirstMessageAt() { return firstMessageAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public int getTotalMessages() { return totalMessages; }
    public double getBestBm25Score() { return bestBm25Score; }
    public List<MatchedMessage> getMatchedMessages() { return matchedMessages; }
    public List<SessionMessage> getFullConversation() { return fullConversation; }

    /**
     * 命中的单条消息（含 BM25 分和预览文本）。
     */
    public static class MatchedMessage {
        private final String id;
        private final String role;
        private final String preview;
        private final double bm25Score;
        private final Instant createdAt;

        public MatchedMessage(String id, String role, String preview, double bm25Score, Instant createdAt) {
            this.id = id;
            this.role = role;
            this.preview = preview;
            this.bm25Score = bm25Score;
            this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public String getRole() { return role; }
        public String getPreview() { return preview; }
        public double getBm25Score() { return bm25Score; }
        public Instant getCreatedAt() { return createdAt; }
    }

    /**
     * 将命中会话格式化为 Agent 可读的对话片段（[USER]: ... [ASSISTANT]: ...）。
     * 超长时按关键词居中截断（见 {@link SessionSearchWindow}）。
     */
    public String formatConversationPreview() {
        return formatConversationPreview(null);
    }

    public String formatConversationPreview(String query) {
        if (fullConversation.isEmpty()) {
            return "(空会话)";
        }
        String body = SessionSearchWindow.formatWithWindow(fullConversation, query, SessionSearchWindow.DEFAULT_MAX_CHARS);
        return "会话 " + conversationId + " (" + totalMessages + " 条消息, best BM25="
                + String.format("%.3f", bestBm25Score) + ")\n" + body;
    }
}
