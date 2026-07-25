package com.bettercli.subagent;

/**
 * 后台子 Agent 完成事件（对齐 1024 完成通知 → bg-react）。
 */
public record CustomSubAgentCompletionEvent(
        String parentConversationId,
        String childSessionId,
        String agentName,
        String toolCallId,
        String task,
        boolean success,
        boolean cancelled,
        String result
) {
    public String statusLabel() {
        if (cancelled) {
            return "❌ cancelled";
        }
        return success ? "✅ done" : "❌ error";
    }
}
