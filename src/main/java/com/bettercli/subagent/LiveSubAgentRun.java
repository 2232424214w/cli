package com.bettercli.subagent;

import com.bettercli.runtime.CancellationToken;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行中 Custom SubAgent 的可变句柄（进度 / 取消 / Future）。
 */
public final class LiveSubAgentRun {
    private final String agentName;
    private final String childSessionId;
    private final String parentSessionId;
    private final Instant startedAt;
    private final String taskPreview;
    private final CancellationToken cancelToken;
    private final AtomicReference<String> lastProgress = new AtomicReference<>("");
    private final AtomicLong lastActiveTimeMs = new AtomicLong(System.currentTimeMillis());
    private volatile Future<?> future;

    public LiveSubAgentRun(String agentName, String childSessionId, String parentSessionId,
                           Instant startedAt, String taskPreview, CancellationToken cancelToken) {
        this.agentName = agentName == null ? "" : agentName;
        this.childSessionId = Objects.requireNonNull(childSessionId, "childSessionId");
        this.parentSessionId = parentSessionId == null ? "(none)" : parentSessionId;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.taskPreview = taskPreview == null ? "" : taskPreview;
        this.cancelToken = cancelToken == null ? new CancellationToken() : cancelToken;
        touch("started");
    }

    public String agentName() {
        return agentName;
    }

    public String childSessionId() {
        return childSessionId;
    }

    public String parentSessionId() {
        return parentSessionId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String taskPreview() {
        return taskPreview;
    }

    public CancellationToken cancelToken() {
        return cancelToken;
    }

    public String lastProgress() {
        return lastProgress.get();
    }

    public long lastActiveTimeMs() {
        return lastActiveTimeMs.get();
    }

    public Future<?> future() {
        return future;
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public void touch(String progress) {
        if (progress != null && !progress.isBlank()) {
            String p = progress.trim();
            lastProgress.set(p.length() > 80 ? p.substring(0, 80) : p);
        }
        lastActiveTimeMs.set(System.currentTimeMillis());
    }

    public CustomSubAgentRunStatus toStatus() {
        return new CustomSubAgentRunStatus(
                agentName, childSessionId, parentSessionId, startedAt, taskPreview,
                lastProgress.get(), lastActiveTimeMs.get());
    }
}
