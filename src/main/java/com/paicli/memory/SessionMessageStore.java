package com.paicli.memory;

import java.io.IOException;
import java.util.List;

/**
 * 历史会话消息存储接口（对标美团 1024 Agent session_messages + session_search）。
 *
 * 职责：
 * 1. 索引历史会话消息到 SQLite FTS5（支持 BM25 全文检索）
 * 2. 提供五阶段检索管道：BM25 检索 → 按会话分组 → 加载完整 → 可选摘要 → 返回
 * 3. 从现有 session_*.jsonl 迁移历史消息
 * 4. 支持按项目 / 时间 / 角色过滤
 *
 * 与 {@link AgentMemoryStore} 的区别：
 * - AgentMemoryStore 存储提炼后的事实（FACT/PATTERN/DEBUG_INSIGHT/WORKFLOW），Agent 自主读写
 * - SessionMessageStore 存储原始对话消息，按会话聚合检索，回溯历史决策
 *
 * 设计参考：docs/memory-system-design.md §3.3 / §4.4 / §5.3
 */
public interface SessionMessageStore extends AutoCloseable {

    /**
     * 索引单条消息。幂等：相同 id 重复写入会被忽略。
     */
    void index(SessionMessage message);

    /**
     * 批量索引消息（迁移场景）。
     *
     * @return 实际写入条目数（跳过已存在的 id）
     */
    int indexBatch(List<SessionMessage> messages);

    /**
     * 五阶段检索管道。
     *
     * ① BM25 全文检索 → topK = limit × 10
     * ② 按 conversation_id 分组，取每个会话最高 BM25 分
     * ③ 加载命中会话的完整消息
     * ④ 截断为 previewChars 预览
     * ⑤ 返回按 BM25 排序的会话列表
     */
    List<SessionMessageSearchResult> search(SessionMessageSearchQuery query);

    /**
     * 加载某个会话的完整消息列表（按时间排序）。
     */
    List<SessionMessage> loadConversation(String conversationId);

    /**
     * 列出所有会话 ID（按最近活跃排序）。
     */
    List<String> listConversations(int limit);

    /**
     * 删除某个会话的所有消息。
     */
    int deleteConversation(String conversationId);

    /**
     * 总消息数。
     */
    int size();

    /**
     * 总会话数。
     */
    int conversationCount();

    /**
     * 从 ~/.paicli/history/session_*.jsonl 迁移历史消息。
     * 幂等：已迁移的会话通过 marker 文件跳过。
     *
     * @return 实际迁移的消息数
     */
    int migrateFromJsonl(java.io.File historyDir) throws IOException;

    @Override
    void close();
}
