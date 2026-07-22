package com.bettercli.memory;

import java.io.IOException;
import java.util.List;

/**
 * PostgreSQL FTS 实现的历史会话消息存储（云端选项，对标美团 1024 Agent session_messages）。
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
 *   <li>表结构对齐 {@link SqliteSessionMessageStore}：{@code session_messages} + {@code session_messages_fts}</li>
 *   <li>PostgreSQL 使用 {@code tsvector} + {@code tsquery} 替代 SQLite FTS5</li>
 *   <li>五阶段管道逻辑复用 {@link SqliteSessionMessageStore#search} 的实现</li>
 * </ul>
 *
 * <p>设计参考：docs/memory-system-design.md §3.3 / §10.4
 *
 * @see MemoryStoreFactory#createSessionMessageStore()
 * @see SqliteSessionMessageStore
 */
public class PostgresSessionMessageStore implements SessionMessageStore {

    public PostgresSessionMessageStore(String jdbcUrl, String user, String password) {
        throw new UnsupportedOperationException(
                "PostgresSessionMessageStore 尚未交付：需要 PostgreSQL JDBC 驱动 + 云端配置。"
                        + "当前请使用 BETTERCLI_MEMORY_BACKEND=sqlite（默认）");
    }

    @Override
    public void index(SessionMessage message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexBatch(List<SessionMessage> messages) {
        return 0;
    }

    @Override
    public List<SessionMessageSearchResult> search(SessionMessageSearchQuery query) {
        return List.of();
    }

    @Override
    public List<SessionMessage> loadConversation(String conversationId) {
        return List.of();
    }

    @Override
    public List<String> listConversations(int limit) {
        return List.of();
    }

    @Override
    public int deleteConversation(String conversationId) {
        return 0;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public int conversationCount() {
        return 0;
    }

    @Override
    public int migrateFromJsonl(java.io.File historyDir) throws IOException {
        return 0;
    }

    @Override
    public void close() {
        // no-op
    }
}
