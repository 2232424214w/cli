package com.bettercli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * PostgreSQL FTS 实现的 Agent 长期记忆存储（云端选项，对标美团 1024 Agent agent_memory 表）。
 *
 * <p>当前状态：<b>骨架预留，尚未交付</b>。需要以下前提：
 * <ul>
 *   <li>引入 PostgreSQL JDBC 驱动依赖（{@code org.postgresql:postgresql}）</li>
 *   <li>配置 {@code BETTERCLI_MEMORY_BACKEND=postgres} + {@code BETTERCLI_POSTGRES_URL/JDBC_USER/JDBC_PASSWORD}</li>
 *   <li>云端 Agent 任务执行场景（本地 CLI 默认走 SQLite）</li>
 * </ul>
 *
 * <p>实现要点（交付时）：
 * <ul>
 *   <li>表结构对齐 {@link SqliteAgentMemoryStore}：{@code agent_memory_entries} + {@code agent_memory_fts}</li>
 *   <li>PostgreSQL 使用 {@code tsvector} + {@code tsquery} 替代 SQLite FTS5</li>
 *   <li>BM25 排序用 {@code ts_rank_cd} 配合 {@code plainto_tsquery}，或引入 {@code pg_trgm} 扩展</li>
 *   <li>confidence 加权公式保持一致：{@code final = -ts_rank_cd * (0.5 + confidence)}</li>
 *   <li>{@code user_vocabulary} 表结构与 SQLite 版一致</li>
 * </ul>
 *
 * <p>设计参考：docs/memory-system-design.md §4.1 / §10.4
 *
 * @see MemoryStoreFactory#createAgentMemoryStore()
 * @see SqliteAgentMemoryStore
 */
public class PostgresAgentMemoryStore implements AgentMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresAgentMemoryStore.class);

    public PostgresAgentMemoryStore(String jdbcUrl, String user, String password) {
        // 骨架预留：交付时在此初始化 PostgreSQL 连接池 + 建表
        throw new UnsupportedOperationException(
                "PostgresAgentMemoryStore 尚未交付：需要 PostgreSQL JDBC 驱动 + 云端配置。"
                        + "当前请使用 BETTERCLI_MEMORY_BACKEND=sqlite（默认）");
    }

    @Override
    public void store(AgentMemoryEntry entry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<AgentMemoryEntry> retrieve(String id) {
        return java.util.Optional.empty();
    }

    @Override
    public List<AgentMemoryEntry> list(MemoryListQuery query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<MemorySearchResult> search(MemorySearchQuery query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean update(String id, MemoryEntryPatch patch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public MemoryStats stats() {
        return MemoryStats.empty();
    }

    @Override
    public void recordUserQuery(String query) {
        // no-op
    }

    @Override
    public double vocabularyBoost(String term) {
        return 1.0;
    }

    @Override
    public int cleanupExpired() {
        return 0;
    }

    @Override
    public java.util.Optional<AgentMemoryEntry> findSimilar(String content, List<String> keywords, double threshold) {
        return java.util.Optional.empty();
    }

    @Override
    public void close() {
        // no-op
    }
}
