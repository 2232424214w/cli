package com.bettercli.memory;

import java.time.Instant;

/**
 * 历史会话检索参数（对标美团 1024 Agent session_search）。
 *
 * 五阶段管道：
 * ① BM25 全文检索 → ② 按会话分组 → ③ 加载完整会话 → ④ 可选 LLM 摘要 → ⑤ 返回
 *
 * 设计参考：docs/memory-system-design.md §4.4 / §5.3
 */
public class SessionMessageSearchQuery {
    private final String query;
    private final int limit;              // 返回会话数，默认 3，最多 10
    private final String roleFilter;      // user / assistant，null = 全部
    private final String project;         // 项目路径过滤，null = 全部项目
    private final Instant since;          // 时间下限，null = 不限
    private final Instant until;          // 时间上限，null = 不限
    private final int topKPerSession;     // 每个会话最多返回多少条命中消息，默认 5
    private final int previewChars;       // 每条消息预览字符数，默认 500

    private SessionMessageSearchQuery(Builder b) {
        this.query = b.query;
        this.limit = clamp(b.limit, 1, 10, 3);
        this.roleFilter = b.roleFilter;
        this.project = b.project;
        this.since = b.since;
        this.until = b.until;
        this.topKPerSession = clamp(b.topKPerSession, 1, 20, 5);
        this.previewChars = clamp(b.previewChars, 50, 10_000, 500);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) return fallback;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static Builder builder() { return new Builder(); }

    public String getQuery() { return query; }
    public int getLimit() { return limit; }
    public String getRoleFilter() { return roleFilter; }
    public String getProject() { return project; }
    public Instant getSince() { return since; }
    public Instant getUntil() { return until; }
    public int getTopKPerSession() { return topKPerSession; }
    public int getPreviewChars() { return previewChars; }

    public static class Builder {
        private String query;
        private int limit;
        private String roleFilter;
        private String project;
        private Instant since;
        private Instant until;
        private int topKPerSession;
        private int previewChars;

        public Builder query(String query) { this.query = query; return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder roleFilter(String roleFilter) { this.roleFilter = roleFilter; return this; }
        public Builder project(String project) { this.project = project; return this; }
        public Builder since(Instant since) { this.since = since; return this; }
        public Builder until(Instant until) { this.until = until; return this; }
        public Builder topKPerSession(int topKPerSession) { this.topKPerSession = topKPerSession; return this; }
        public Builder previewChars(int previewChars) { this.previewChars = previewChars; return this; }

        public SessionMessageSearchQuery build() { return new SessionMessageSearchQuery(this); }
    }
}
