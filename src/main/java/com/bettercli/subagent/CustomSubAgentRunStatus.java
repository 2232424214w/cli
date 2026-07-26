package com.bettercli.subagent;

import java.time.Instant;

/**
 * 正在运行的 Custom SubAgent 快照（供 /subagent status 与 running_agents_list）。
 */
public record CustomSubAgentRunStatus(
        String agentName,
        String childSessionId,
        String parentSessionId,
        Instant startedAt,
        String taskPreview,
        String lastProgress,
        long lastActiveTimeMs
) {
    public CustomSubAgentRunStatus {
        if (agentName == null) {
            agentName = "";
        }
        if (childSessionId == null) {
            childSessionId = "";
        }
        if (parentSessionId == null) {
            parentSessionId = "";
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (taskPreview == null) {
            taskPreview = "";
        }
        if (lastProgress == null) {
            lastProgress = "";
        }
    }

    /** 兼容旧 5 字段构造。 */
    public CustomSubAgentRunStatus(String agentName, String childSessionId, String parentSessionId,
                                   Instant startedAt, String taskPreview) {
        this(agentName, childSessionId, parentSessionId, startedAt, taskPreview, "",
                startedAt == null ? System.currentTimeMillis() : startedAt.toEpochMilli());
    }
}
