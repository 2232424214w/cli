package com.bettercli.subagent;

import java.time.Instant;

/**
 * 正在运行的 Custom SubAgent 快照（供 /subagent status 查看）。
 */
public record CustomSubAgentRunStatus(
        String agentName,
        String childSessionId,
        String parentSessionId,
        Instant startedAt,
        String taskPreview
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
    }
}
