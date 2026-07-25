package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把当前会话的 conversationHistory 异步索引到 {@link SessionMessageStore}。
 *
 * <p>所有 {@code lastIndex}/{@code indexEpoch} 变更与写库都串行跑在单线程 executor 上，
 * 避免 Mid-Turn 压缩异步重建游标与 end-of-turn 增量索引互相踩踏。
 */
public class SessionMessageIndexer {
    private static final Logger log = LoggerFactory.getLogger(SessionMessageIndexer.class);

    private final SessionMessageStore store;
    private final String conversationId;
    private final String projectPath;
    private final ExecutorService executor;
    private final AtomicInteger lastIndex = new AtomicInteger(0);
    /** 压缩轮次前缀，避免压缩后 history 下标与旧 FTS id 碰撞导致幂等跳过。 */
    private volatile long indexEpoch = 0L;
    private volatile boolean closed = false;

    public SessionMessageIndexer(SessionMessageStore store, String conversationId, String projectPath) {
        this.store = store;
        this.conversationId = conversationId == null ? generateConversationId() : conversationId;
        this.projectPath = projectPath;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bettercli-session-indexer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 异步索引新增消息（只写入 lastIndex 之后的消息）。
     * 调用方传入当前 conversationHistory 的快照。
     */
    public CompletableFuture<Integer> indexIncremental(List<LlmClient.Message> history) {
        if (closed || store == null || history == null || history.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        List<LlmClient.Message> snap = List.copyOf(history);
        return CompletableFuture.supplyAsync(() -> indexIncrementalOnWorker(snap), executor);
    }

    /**
     * 同步索引（测试或关闭前 flush 用）。在 indexer 线程上执行以保持串行语义。
     */
    public int indexIncrementalSync(List<LlmClient.Message> history) {
        if (closed || store == null || history == null || history.isEmpty()) {
            return 0;
        }
        List<LlmClient.Message> snap = List.copyOf(history);
        try {
            return CompletableFuture.supplyAsync(() -> indexIncrementalOnWorker(snap), executor).join();
        } catch (Exception e) {
            log.warn("同步索引会话消息失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 压缩检查点落库（对标 1024 Step 6）并重建增量游标。
     */
    public CompletableFuture<Integer> indexCompacted(
            ConversationHistoryCompactor.CompactCheckpoint checkpoint,
            List<LlmClient.Message> historyAfterCompact) {
        if (closed || store == null) {
            resetIndex(historyAfterCompact == null ? 0 : historyAfterCompact.size());
            return CompletableFuture.completedFuture(0);
        }
        List<LlmClient.Message> snap = historyAfterCompact == null
                ? List.of()
                : List.copyOf(historyAfterCompact);
        return CompletableFuture.supplyAsync(
                () -> indexCompactedOnWorker(checkpoint, snap), executor);
    }

    /** 同步版本（测试用）。 */
    public int indexCompactedSync(ConversationHistoryCompactor.CompactCheckpoint checkpoint,
                                  List<LlmClient.Message> historyAfterCompact) {
        if (closed || store == null) {
            resetIndex(historyAfterCompact == null ? 0 : historyAfterCompact.size());
            return 0;
        }
        List<LlmClient.Message> snap = historyAfterCompact == null
                ? List.of()
                : List.copyOf(historyAfterCompact);
        try {
            return CompletableFuture.supplyAsync(
                    () -> indexCompactedOnWorker(checkpoint, snap), executor).join();
        } catch (Exception e) {
            log.warn("同步索引压缩检查点失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 压缩或 /clear 后重置增量游标（经 worker 串行）。 */
    public void resetIndex(int index) {
        int safe = Math.max(0, index);
        if (closed) {
            lastIndex.set(safe);
            return;
        }
        try {
            CompletableFuture.runAsync(() -> lastIndex.set(safe), executor).join();
        } catch (Exception e) {
            lastIndex.set(safe);
        }
    }

    /**
     * Resume / 重建后从 0 全量回填索引（换 epoch，幂等写入新 id）。
     * 避免「jsonl 有、FTS 无」时仍把游标推到末尾导致永久不可检索。
     */
    public int reindexFromStart(List<LlmClient.Message> history) {
        if (closed || store == null || history == null) {
            return 0;
        }
        List<LlmClient.Message> snap = List.copyOf(history);
        try {
            return CompletableFuture.supplyAsync(() -> {
                indexEpoch = System.currentTimeMillis();
                lastIndex.set(0);
                return indexIncrementalOnWorker(snap);
            }, executor).join();
        } catch (Exception e) {
            log.warn("全量回填会话索引失败: {}", e.getMessage());
            return 0;
        }
    }

    private int indexIncrementalOnWorker(List<LlmClient.Message> history) {
        if (closed || store == null || history.isEmpty()) {
            return 0;
        }
        int from = lastIndex.get();
        if (from > history.size()) {
            // 历史被压缩缩短：换 epoch，从 0 重建本段，避免静默 no-op 漏索引
            indexEpoch = System.currentTimeMillis();
            from = 0;
            log.debug("session index realigned after shrink: size={}, epoch={}", history.size(), indexEpoch);
        }
        if (from >= history.size()) {
            return 0;
        }
        List<SessionMessage> batch = buildBatch(history, from);
        if (batch.isEmpty() || closed) {
            // 本段无可写内容（全是 system/空），游标仍可推进，避免反复空转
            lastIndex.set(history.size());
            return 0;
        }
        try {
            int written = store.indexBatch(batch);
            lastIndex.set(history.size());
            return written;
        } catch (Exception e) {
            log.warn("索引会话消息失败: {}", e.getMessage());
            // 失败不推进游标，允许下次 flush 重试
            return 0;
        }
    }

    private int indexCompactedOnWorker(ConversationHistoryCompactor.CompactCheckpoint checkpoint,
                                       List<LlmClient.Message> historyAfterCompact) {
        if (closed || store == null) {
            lastIndex.set(historyAfterCompact.size());
            return 0;
        }
        int written = 0;
        if (checkpoint != null) {
            String summary = checkpoint.summaryText() == null ? "" : checkpoint.summaryText().trim();
            if (!summary.isBlank()) {
                try {
                    store.index(SessionMessage.builder()
                            .id(conversationId + "-compacted-" + System.currentTimeMillis())
                            .conversationId(conversationId)
                            .role("user")
                            .content(summary)
                            .project(projectPath)
                            .createdAt(Instant.now())
                            .build());
                    written++;
                } catch (Exception e) {
                    log.warn("索引压缩摘要失败: {}", e.getMessage());
                }
            }
        }
        indexEpoch = System.currentTimeMillis();
        lastIndex.set(0);
        written += indexIncrementalOnWorker(historyAfterCompact);
        return written;
    }

    private List<SessionMessage> buildBatch(List<LlmClient.Message> history, int from) {
        List<SessionMessage> batch = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            LlmClient.Message msg = history.get(i);
            if (msg == null || "system".equals(msg.role())) continue;
            String content = msg.content() == null ? "" : msg.content();
            if (content.isBlank() && msg.toolCalls() == null) continue;

            String toolCallsJson = SessionMessage.serializeToolCalls(msg.toolCalls());
            String id = indexEpoch > 0
                    ? conversationId + "-" + indexEpoch + "-" + i
                    : conversationId + "-" + i;
            batch.add(SessionMessage.builder()
                    .id(id)
                    .conversationId(conversationId)
                    .role(msg.role())
                    .content(content)
                    .toolCallsJson(toolCallsJson)
                    .toolCallId(msg.toolCallId())
                    .project(projectPath)
                    .createdAt(Instant.now())
                    .build());
        }
        return batch;
    }

    public String getConversationId() {
        return conversationId;
    }

    public int getLastIndex() {
        return lastIndex.get();
    }

    public static String generateConversationId() {
        return "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 关闭索引器，等待异步任务完成。
     */
    public void close() {
        closed = true;
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
