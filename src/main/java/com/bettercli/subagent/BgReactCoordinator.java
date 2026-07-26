package com.bettercli.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台子 Agent 完成后的 bg-react 调度与去重（对齐 1024：session 写入时间 vs 上次 bg-react 开始时间）。
 *
 * <p>单机内存实现，不依赖 Redis。同一 conversationId 串行执行 bg-react。
 */
public final class BgReactCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BgReactCoordinator.class);

    private final Map<String, Long> sessionWriteMillis = new ConcurrentHashMap<>();
    private final Map<String, Long> lastBgReactStartMillis = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bettercli-bg-react");
        t.setDaemon(true);
        return t;
    });

    public void markSessionWrite(String conversationId) {
        sessionWriteMillis.put(key(conversationId), System.currentTimeMillis());
    }

    /**
     * 提交 bg-react。若上一轮开始时已覆盖本次写入，则跳过。
     *
     * @param task 返回推送给用户的文本（可空）；异常时记日志
     */
    public void enqueue(String conversationId, BgReactTask task) {
        Objects.requireNonNull(task, "task");
        String k = key(conversationId);
        executor.execute(() -> runOne(k, task));
    }

    private void runOne(String key, BgReactTask task) {
        Object lock = locks.computeIfAbsent(key, x -> new Object());
        synchronized (lock) {
            Long write = sessionWriteMillis.get(key);
            Long lastStart = lastBgReactStartMillis.get(key);
            if (write != null && lastStart != null && write <= lastStart) {
                log.debug("bg-react skip duplicate key={} write={} lastStart={}", key, write, lastStart);
                return;
            }
            AtomicBoolean flag = running.computeIfAbsent(key, x -> new AtomicBoolean(false));
            if (!flag.compareAndSet(false, true)) {
                // 同会话已有 bg-react 在跑：稍后重试（新的 write 时间已更新）
                executor.execute(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    runOne(key, task);
                });
                return;
            }
            lastBgReactStartMillis.put(key, System.currentTimeMillis());
            try {
                String reply = task.run();
                task.onReply(reply);
            } catch (Exception e) {
                log.warn("bg-react failed key={}: {}", key, e.getMessage());
                try {
                    task.onReply("bg-react 失败: " + e.getMessage());
                } catch (Exception ignored) {
                }
            } finally {
                flag.set(false);
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static String key(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || "(none)".equals(conversationId)) {
            return "_default";
        }
        return conversationId.trim();
    }

    @FunctionalInterface
    public interface BgReactTask {
        String run() throws Exception;

        default void onReply(String reply) {
        }
    }
}
