package com.bettercli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 历史会话消息条目（对标美团 1024 Agent session_messages 表）。
 *
 * 与 {@link com.bettercli.tui.history.ConversationSnapshot.MessageRecord} 的关系：
 * - ConversationSnapshot 是 TUI 专用的 JSONL 持久化，每会话一个文件
 * - SessionMessage 是 SQLite FTS5 索引的统一存储，支持跨会话 BM25 检索
 * - 启动时从 session_*.jsonl 迁移到 SQLite，新消息双写
 *
 * 设计参考：docs/memory-system-design.md §3.3
 */
public class SessionMessage {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String conversationId;
    private final String role;          // user / assistant / tool
    private final String content;
    private final String toolCallsJson; // assistant 的 tool_calls（可选）
    private final String toolCallId;    // tool 消息的 call_id（可选）
    private final String project;
    private final Instant createdAt;
    private final int tokenCount;

    public SessionMessage(String id, String conversationId, String role, String content,
                          String toolCallsJson, String toolCallId, String project,
                          Instant createdAt, int tokenCount) {
        this.id = Objects.requireNonNull(id, "id");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.role = Objects.requireNonNull(role, "role");
        this.content = content == null ? "" : content;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.project = project;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.tokenCount = tokenCount > 0 ? tokenCount : estimateTokens(content);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCallsJson() { return toolCallsJson; }
    public String getToolCallId() { return toolCallId; }
    public String getProject() { return project; }
    public Instant getCreatedAt() { return createdAt; }
    public int getTokenCount() { return tokenCount; }

    /**
     * 粗略估算 token 数（与 AgentMemoryEntry.estimateTokens 保持一致）。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    /**
     * 序列化 tool_calls 列表为 JSON 字符串，供 SQLite 存储。
     */
    public static String serializeToolCalls(List<?> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(toolCalls);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "[" + role + "@" + conversationId + "] "
                + (content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }

    public static class Builder {
        private String id;
        private String conversationId;
        private String role;
        private String content;
        private String toolCallsJson;
        private String toolCallId;
        private String project;
        private Instant createdAt;
        private int tokenCount;

        public Builder id(String id) { this.id = id; return this; }
        public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
        public Builder role(String role) { this.role = role; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder toolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; return this; }
        public Builder toolCallId(String toolCallId) { this.toolCallId = toolCallId; return this; }
        public Builder project(String project) { this.project = project; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder tokenCount(int tokenCount) { this.tokenCount = tokenCount; return this; }

        public SessionMessage build() {
            return new SessionMessage(id, conversationId, role, content,
                    toolCallsJson, toolCallId, project, createdAt, tokenCount);
        }
    }
}
