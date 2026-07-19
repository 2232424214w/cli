package com.paicli.memory;

import com.paicli.llm.LlmClient;
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
 * 设计目标：
 * 1. 不阻塞主 ReAct 循环：用独立线程池异步写入
 * 2. 增量索引：跟踪上次索引位置，只写入新增消息
 * 3. 跳过 system 消息：system prompt 不参与历史检索
 * 4. 每条消息生成稳定 id（conversationId + index），保证幂等
 *
 * 设计参考：docs/memory-system-design.md §5.3 / §10.3 M3.3
 */
public class SessionMessageIndexer {
    private static final Logger log = LoggerFactory.getLogger(SessionMessageIndexer.class);

    private final SessionMessageStore store;
    private final String conversationId;
    private final String projectPath;
    private final ExecutorService executor;
    private final AtomicInteger lastIndex = new AtomicInteger(0);
    private volatile boolean closed = false;

    public SessionMessageIndexer(SessionMessageStore store, String conversationId, String projectPath) {
        this.store = store;
        this.conversationId = conversationId == null ? generateConversationId() : conversationId;
        this.projectPath = projectPath;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "paicli-session-indexer");
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
        int from = lastIndex.get();
        if (from >= history.size()) {
            return CompletableFuture.completedFuture(0);
        }

        List<SessionMessage> batch = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            LlmClient.Message msg = history.get(i);
            if (msg == null || "system".equals(msg.role())) continue;
            String content = msg.content() == null ? "" : msg.content();
            if (content.isBlank() && msg.toolCalls() == null) continue;

            String toolCallsJson = SessionMessage.serializeToolCalls(msg.toolCalls());
            batch.add(SessionMessage.builder()
                    .id(conversationId + "-" + i)
                    .conversationId(conversationId)
                    .role(msg.role())
                    .content(content)
                    .toolCallsJson(toolCallsJson)
                    .toolCallId(msg.toolCallId())
                    .project(projectPath)
                    .createdAt(Instant.now())
                    .build());
        }

        if (batch.isEmpty()) {
            lastIndex.set(history.size());
            return CompletableFuture.completedFuture(0);
        }

        lastIndex.set(history.size());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.indexBatch(batch);
            } catch (Exception e) {
                log.warn("异步索引会话消息失败: {}", e.getMessage());
                return 0;
            }
        }, executor);
    }

    /**
     * 同步索引（测试或关闭前 flush 用）。
     */
    public int indexIncrementalSync(List<LlmClient.Message> history) {
        if (closed || store == null || history == null || history.isEmpty()) return 0;
        int from = lastIndex.get();
        if (from >= history.size()) return 0;

        List<SessionMessage> batch = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            LlmClient.Message msg = history.get(i);
            if (msg == null || "system".equals(msg.role())) continue;
            String content = msg.content() == null ? "" : msg.content();
            if (content.isBlank() && msg.toolCalls() == null) continue;

            batch.add(SessionMessage.builder()
                    .id(conversationId + "-" + i)
                    .conversationId(conversationId)
                    .role(msg.role())
                    .content(content)
                    .toolCallsJson(SessionMessage.serializeToolCalls(msg.toolCalls()))
                    .toolCallId(msg.toolCallId())
                    .project(projectPath)
                    .createdAt(Instant.now())
                    .build());
        }
        lastIndex.set(history.size());
        if (batch.isEmpty()) return 0;
        return store.indexBatch(batch);
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
