package com.paicli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 记忆维护调度器：定期执行 TTL 清理、容量统计等后台任务。
 *
 * 设计参考：docs/memory-system-design.md §6 护栏机制
 *
 * 默认每小时执行一次 cleanupExpired，清理过期和 pending 超时的记忆条目。
 * 可通过 paicli.memory.cleanup.interval.hours 系统属性调整间隔。
 */
public class MemoryMaintenanceScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceScheduler.class);

    private static final long DEFAULT_INTERVAL_HOURS = 1;
    private static final long MIN_INTERVAL_SECONDS = 60;

    private final AgentMemoryStore store;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MemoryMaintenanceScheduler(AgentMemoryStore store) {
        this(store, resolveIntervalHours());
    }

    public MemoryMaintenanceScheduler(AgentMemoryStore store, long intervalHours) {
        this.store = store;
        long intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, TimeUnit.HOURS.toSeconds(
                Math.max(1, intervalHours)));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "paicli-memory-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::runMaintenance, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        running.set(true);
        log.info("Agent 记忆维护调度器已启动，间隔 {} 秒", intervalSeconds);
    }

    private void runMaintenance() {
        if (!running.get()) {
            return;
        }
        try {
            int deleted = store.cleanupExpired();
            if (deleted > 0) {
                log.info("记忆维护：清理了 {} 条过期记忆", deleted);
            }
        } catch (Exception e) {
            log.warn("记忆维护任务执行失败: {}", e.getMessage());
        }
    }

    /**
     * 立即执行一次维护（用于测试或手动触发）。
     */
    public int runMaintenanceNow() {
        return store.cleanupExpired();
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("记忆维护调度器未在 5 秒内终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long resolveIntervalHours() {
        String configured = System.getProperty("paicli.memory.cleanup.interval.hours");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Long.parseLong(configured.trim()));
            } catch (NumberFormatException ignored) {
                log.warn("Invalid paicli.memory.cleanup.interval.hours: {}", configured);
            }
        }
        String env = System.getenv("PAICLI_MEMORY_CLEANUP_INTERVAL_HOURS");
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(1, Long.parseLong(env.trim()));
            } catch (NumberFormatException ignored) {
                log.warn("Invalid PAICLI_MEMORY_CLEANUP_INTERVAL_HOURS: {}", env);
            }
        }
        return DEFAULT_INTERVAL_HOURS;
    }
}
