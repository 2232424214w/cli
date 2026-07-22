package com.bettercli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Agent 维护的长期记忆条目（对标美团 1024 Agent agent_memory 表）。
 *
 * 与 {@link MemoryEntry} 的区别：
 * - 新增 keywords / scope / project / confidence / source / status / access_count 等字段
 * - 支持 BM25 全文检索（content + keywords 拼接后索引）
 * - 支持 confidence 加权打分
 * - 支持 pending / active / expired 状态机（pending 待确认，active 可检索，expired 自动清理）
 *
 * 设计参考：docs/memory-system-design.md §3.2
 */
public class AgentMemoryEntry {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String content;
    private final List<String> keywords;
    private final MemoryType type;
    private final MemoryScope scope;
    private final String project;
    private final double confidence;
    private final MemorySource source;
    private final MemoryStatus status;
    private final Instant pendingExpiresAt;
    private final int tokenCount;
    private final int accessCount;
    private final Instant lastAccessedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    public enum MemoryType {
        FACT,           // 稳定事实：项目用 SQLite 不用 PostgreSQL
        PATTERN,        // 任务模式：用户喜欢先看测试再改代码
        DEBUG_INSIGHT,  // 调试经验：Agent.java 的 run loop 在并发场景容易出 X 问题
        WORKFLOW        // 工作流习惯：用户提交前喜欢跑 mvn test -Pquick
    }

    public enum MemoryScope {
        PROJECT,  // 项目级作用域
        GLOBAL    // 跨项目通用
    }

    public enum MemorySource {
        AGENT_TOOL,      // Agent 通过 agent_memory_save 工具保存
        EXPLICIT_HINT,   // 用户明确说"记一下/记住"触发
        MIGRATED          // 从 long_term_memory.json 迁移
    }

    public enum MemoryStatus {
        ACTIVE,   // 可被检索
        PENDING,  // 待确认（低 confidence 时暂存，超时自动清理）
        EXPIRED   // 已过期（TTL 清理任务标记，等待删除）
    }

    private AgentMemoryEntry(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.content = Objects.requireNonNull(builder.content, "content");
        this.keywords = builder.keywords == null ? List.of() : List.copyOf(builder.keywords);
        this.type = builder.type == null ? MemoryType.FACT : builder.type;
        this.scope = builder.scope == null ? MemoryScope.PROJECT : builder.scope;
        this.project = builder.project;
        this.confidence = clampConfidence(builder.confidence);
        this.source = builder.source == null ? MemorySource.AGENT_TOOL : builder.source;
        this.status = builder.status == null ? MemoryStatus.ACTIVE : builder.status;
        this.pendingExpiresAt = builder.pendingExpiresAt;
        this.tokenCount = builder.tokenCount > 0 ? builder.tokenCount : estimateTokens(content);
        this.accessCount = Math.max(0, builder.accessCount);
        this.lastAccessedAt = builder.lastAccessedAt;
        this.createdAt = builder.createdAt == null ? Instant.now() : builder.createdAt;
        this.updatedAt = builder.updatedAt == null ? this.createdAt : builder.updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public List<String> getKeywords() { return keywords; }
    public MemoryType getType() { return type; }
    public MemoryScope getScope() { return scope; }
    public String getProject() { return project; }
    public double getConfidence() { return confidence; }
    public MemorySource getSource() { return source; }
    public MemoryStatus getStatus() { return status; }
    public Instant getPendingExpiresAt() { return pendingExpiresAt; }
    public int getTokenCount() { return tokenCount; }
    public int getAccessCount() { return accessCount; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * 序列化 keywords 为 JSON 数组字符串，供 SQLite 存储。
     */
    public String keywordsJson() {
        try {
            return MAPPER.writeValueAsString(keywords);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 反序列化 keywords JSON 字符串。
     */
    public static List<String> parseKeywords(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * 拼接 content + keywords 作为 BM25 检索的文档文本。
     * keywords 用空格分隔重复一次，提升专有名词权重。
     */
    public String searchableText() {
        StringBuilder sb = new StringBuilder(content);
        if (!keywords.isEmpty()) {
            sb.append(" ").append(String.join(" ", keywords));
        }
        return sb.toString();
    }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）。
     * 与 {@link MemoryEntry#estimateTokens(String)} 保持一致。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    private static double clampConfidence(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }

    @Override
    public String toString() {
        return "[" + type + "/" + scope + "] " + id + ": "
                + (content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }

    public static class Builder {
        private String id;
        private String content;
        private List<String> keywords = new ArrayList<>();
        private MemoryType type;
        private MemoryScope scope;
        private String project;
        private double confidence = 0.5;
        private MemorySource source;
        private MemoryStatus status;
        private Instant pendingExpiresAt;
        private int tokenCount;
        private int accessCount;
        private Instant lastAccessedAt;
        private Instant createdAt;
        private Instant updatedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder keywords(List<String> keywords) {
            this.keywords = keywords == null ? Collections.emptyList() : new ArrayList<>(keywords);
            return this;
        }
        public Builder addKeyword(String keyword) {
            if (keyword != null && !keyword.isBlank()) {
                if (this.keywords == null) this.keywords = new ArrayList<>();
                this.keywords.add(keyword.trim());
            }
            return this;
        }
        public Builder type(MemoryType type) { this.type = type; return this; }
        public Builder scope(MemoryScope scope) { this.scope = scope; return this; }
        public Builder project(String project) { this.project = project; return this; }
        public Builder confidence(double confidence) { this.confidence = confidence; return this; }
        public Builder source(MemorySource source) { this.source = source; return this; }
        public Builder status(MemoryStatus status) { this.status = status; return this; }
        public Builder pendingExpiresAt(Instant pendingExpiresAt) { this.pendingExpiresAt = pendingExpiresAt; return this; }
        public Builder tokenCount(int tokenCount) { this.tokenCount = tokenCount; return this; }
        public Builder accessCount(int accessCount) { this.accessCount = accessCount; return this; }
        public Builder lastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public AgentMemoryEntry build() {
            return new AgentMemoryEntry(this);
        }
    }
}
