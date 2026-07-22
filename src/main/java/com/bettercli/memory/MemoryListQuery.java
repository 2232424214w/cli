package com.bettercli.memory;

/**
 * Agent 记忆列表查询参数（用于 /agent-memory list 命令）。
 *
 * 与 {@link MemorySearchQuery} 的区别：list 不做 BM25 检索，只按字段过滤分页。
 */
public class MemoryListQuery {
    private final int offset;
    private final int limit;
    private final AgentMemoryEntry.MemoryType type;
    private final AgentMemoryEntry.MemoryScope scope;
    private final String project;
    private final AgentMemoryEntry.MemoryStatus status;
    private final String orderBy;  // created_at / updated_at / confidence / access_count

    private MemoryListQuery(Builder builder) {
        this.offset = Math.max(0, builder.offset);
        this.limit = builder.limit > 0 ? Math.min(builder.limit, 200) : 50;
        this.type = builder.type;
        this.scope = builder.scope;
        this.project = builder.project;
        this.status = builder.status;
        this.orderBy = builder.orderBy == null || builder.orderBy.isBlank() ? "created_at" : builder.orderBy;
    }

    public int getOffset() { return offset; }
    public int getLimit() { return limit; }
    public AgentMemoryEntry.MemoryType getType() { return type; }
    public AgentMemoryEntry.MemoryScope getScope() { return scope; }
    public String getProject() { return project; }
    public AgentMemoryEntry.MemoryStatus getStatus() { return status; }
    public String getOrderBy() { return orderBy; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int offset;
        private int limit = 50;
        private AgentMemoryEntry.MemoryType type;
        private AgentMemoryEntry.MemoryScope scope;
        private String project;
        private AgentMemoryEntry.MemoryStatus status;
        private String orderBy;

        public Builder offset(int offset) { this.offset = offset; return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder type(AgentMemoryEntry.MemoryType type) { this.type = type; return this; }
        public Builder scope(AgentMemoryEntry.MemoryScope scope) { this.scope = scope; return this; }
        public Builder project(String project) { this.project = project; return this; }
        public Builder status(AgentMemoryEntry.MemoryStatus status) { this.status = status; return this; }
        public Builder orderBy(String orderBy) { this.orderBy = orderBy; return this; }

        public MemoryListQuery build() {
            return new MemoryListQuery(this);
        }
    }
}
