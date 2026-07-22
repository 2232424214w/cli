package com.bettercli.memory;

import java.time.Instant;

/**
 * Agent 记忆检索查询参数。
 *
 * 设计参考：docs/memory-system-design.md §4.1 / §5.2
 */
public class MemorySearchQuery {
    private final String query;
    private final int limit;
    private final AgentMemoryEntry.MemoryType type;
    private final AgentMemoryEntry.MemoryScope scope;
    private final String project;
    private final Double minConfidence;
    private final Instant createdAfter;
    private final Instant createdBefore;
    private final boolean includePending;

    private MemorySearchQuery(Builder builder) {
        this.query = builder.query;
        this.limit = builder.limit > 0 ? Math.min(builder.limit, 50) : 5;
        this.type = builder.type;
        this.scope = builder.scope;
        this.project = builder.project;
        this.minConfidence = builder.minConfidence;
        this.createdAfter = builder.createdAfter;
        this.createdBefore = builder.createdBefore;
        this.includePending = builder.includePending;
    }

    public String getQuery() { return query; }
    public int getLimit() { return limit; }
    public AgentMemoryEntry.MemoryType getType() { return type; }
    public AgentMemoryEntry.MemoryScope getScope() { return scope; }
    public String getProject() { return project; }
    public Double getMinConfidence() { return minConfidence; }
    public Instant getCreatedAfter() { return createdAfter; }
    public Instant getCreatedBefore() { return createdBefore; }
    public boolean isIncludePending() { return includePending; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String query;
        private int limit = 5;
        private AgentMemoryEntry.MemoryType type;
        private AgentMemoryEntry.MemoryScope scope;
        private String project;
        private Double minConfidence;
        private Instant createdAfter;
        private Instant createdBefore;
        private boolean includePending = false;

        public Builder query(String query) { this.query = query; return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder type(AgentMemoryEntry.MemoryType type) { this.type = type; return this; }
        public Builder scope(AgentMemoryEntry.MemoryScope scope) { this.scope = scope; return this; }
        public Builder project(String project) { this.project = project; return this; }
        public Builder minConfidence(double minConfidence) { this.minConfidence = minConfidence; return this; }
        public Builder createdAfter(Instant createdAfter) { this.createdAfter = createdAfter; return this; }
        public Builder createdBefore(Instant createdBefore) { this.createdBefore = createdBefore; return this; }
        public Builder includePending(boolean includePending) { this.includePending = includePending; return this; }

        public MemorySearchQuery build() {
            return new MemorySearchQuery(this);
        }
    }
}
