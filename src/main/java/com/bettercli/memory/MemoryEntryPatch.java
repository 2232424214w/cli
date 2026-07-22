package com.bettercli.memory;

import java.util.List;

/**
 * Agent 记忆条目更新补丁（部分字段更新）。
 *
 * null 字段表示不更新该字段。
 */
public class MemoryEntryPatch {
    private final String content;
    private final List<String> keywords;
    private final AgentMemoryEntry.MemoryType type;
    private final AgentMemoryEntry.MemoryScope scope;
    private final Double confidence;
    private final AgentMemoryEntry.MemoryStatus status;

    private MemoryEntryPatch(Builder builder) {
        this.content = builder.content;
        this.keywords = builder.keywords;
        this.type = builder.type;
        this.scope = builder.scope;
        this.confidence = builder.confidence;
        this.status = builder.status;
    }

    public String getContent() { return content; }
    public List<String> getKeywords() { return keywords; }
    public AgentMemoryEntry.MemoryType getType() { return type; }
    public AgentMemoryEntry.MemoryScope getScope() { return scope; }
    public Double getConfidence() { return confidence; }
    public AgentMemoryEntry.MemoryStatus getStatus() { return status; }

    public boolean isEmpty() {
        return content == null && keywords == null && type == null
                && scope == null && confidence == null && status == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String content;
        private List<String> keywords;
        private AgentMemoryEntry.MemoryType type;
        private AgentMemoryEntry.MemoryScope scope;
        private Double confidence;
        private AgentMemoryEntry.MemoryStatus status;

        public Builder content(String content) { this.content = content; return this; }
        public Builder keywords(List<String> keywords) { this.keywords = keywords; return this; }
        public Builder type(AgentMemoryEntry.MemoryType type) { this.type = type; return this; }
        public Builder scope(AgentMemoryEntry.MemoryScope scope) { this.scope = scope; return this; }
        public Builder confidence(double confidence) { this.confidence = confidence; return this; }
        public Builder status(AgentMemoryEntry.MemoryStatus status) { this.status = status; return this; }

        public MemoryEntryPatch build() {
            return new MemoryEntryPatch(this);
        }
    }
}
