package com.bettercli.memory;

import java.util.List;
import java.util.Optional;

/**
 * Agent 维护的长期记忆存储接口（对标美团 1024 Agent agent_memory 表）。
 *
 * 设计目标：
 * - 可插拔后端：默认 SQLite FTS5，未来可扩展 PostgreSQL FTS / Milvus BM25
 * - BM25 检索 + confidence 加权打分
 * - 容量护栏、自动去重、TTL 清理由实现层负责
 * - 词汇表累积（user_vocabulary）由实现层负责持久化
 *
 * 设计参考：docs/memory-system-design.md §4.1
 */
public interface AgentMemoryStore extends AutoCloseable {

    // ==================== CRUD ====================

    /**
     * 保存一条记忆条目。若 id 已存在则覆盖。
     * 实现层应：
     * - 检查容量上限（默认 1000 条），超限时拒绝并抛出 IllegalStateException
     * - 检查自动去重（BM25 相似度 > 阈值时合并或拒绝）
     * - 同步写入 FTS 索引（或通过触发器）
     */
    void store(AgentMemoryEntry entry);

    /**
     * 按 id 检索单条记忆。
     */
    Optional<AgentMemoryEntry> retrieve(String id);

    /**
     * 按 id 更新记忆条目的部分字段。返回是否实际更新成功（id 不存在返回 false）。
     * 实现层应同步刷新 FTS 索引和 updated_at。
     */
    boolean update(String id, MemoryEntryPatch patch);

    /**
     * 按 id 删除记忆条目。返回是否实际删除成功（id 不存在返回 false）。
     * 实现层应同步删除 FTS 索引。
     */
    boolean delete(String id);

    /**
     * 清空所有记忆条目（谨慎操作，通常由 /agent-memory clear 调用）。
     */
    void clear();

    // ==================== 检索 ====================

    /**
     * BM25 检索 + confidence 加权打分。
     * 实现层应：
     * - 用 FTS5 bm25() 函数计算 BM25 分数
     * - 用 confidence 作为加权因子（final_score = bm25 * (1 + confidence)）
     * - 应用 user_vocabulary boost（用户提过的关键词加权）
     * - 应用 scope/project 过滤
     * - 按 final_score 降序返回
     * - 命中时更新 access_count 和 last_accessed_at
     */
    List<MemorySearchResult> search(MemorySearchQuery query);

    /**
     * 列表查询（不做 BM25 检索，只按字段过滤分页）。
     * 用于 /agent-memory list 命令展示。
     */
    List<AgentMemoryEntry> list(MemoryListQuery query);

    // ==================== 统计 ====================

    /**
     * 总条目数。
     */
    int size();

    /**
     * 详细统计信息。
     */
    MemoryStats stats();

    // ==================== 词汇表 ====================

    /**
     * 记录用户查询中的关键词到词汇表（用于后续检索 boost）。
     * 实现层应去重、统计频次。
     */
    void recordUserQuery(String query);

    /**
     * 计算某个词的 vocabulary boost 值（用户提过的词 boost > 1.0，未提过 = 1.0）。
     */
    double vocabularyBoost(String term);

    // ==================== 护栏 ====================

    /**
     * 清理过期记忆（status=expired 或 pending 超时未确认）。
     * 通常由后台 TTL 清理任务定期调用。
     * 返回清理掉的条目数。
     */
    int cleanupExpired();

    /**
     * 检测与给定 content + keywords 高度相似的记忆条目（用于自动去重）。
     * 返回最相似的条目（若无相似返回 Optional.empty()）。
     */
    Optional<AgentMemoryEntry> findSimilar(String content, List<String> keywords, double similarityThreshold);
}
