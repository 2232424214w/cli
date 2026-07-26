package com.bettercli.subagent;

/**
 * 后台子 Agent 完成事件（对齐 1024 完成通知 → bg-react）。
 *
 * <p>{@code parentSessionEpoch} 为启动子 Agent 时主会话的世代；主 Agent {@code /clear} 后递增，
 * 迟到的完成通知若 epoch 不匹配则丢弃，避免污染新会话。
 */
public record CustomSubAgentCompletionEvent(
        String parentConversationId,
        String childSessionId,
        String agentName,
        String toolCallId,
        String task,
        boolean success,
        boolean cancelled,
        String result,
        long parentSessionEpoch
) {
    public CustomSubAgentCompletionEvent(
            String parentConversationId,
            String childSessionId,
            String agentName,
            String toolCallId,
            String task,
            boolean success,
            boolean cancelled,
            String result) {
        this(parentConversationId, childSessionId, agentName, toolCallId, task,
                success, cancelled, result, 0L);
    }

    public String statusLabel() {
        if (cancelled) {
            return "❌ cancelled";
        }
        return success ? "✅ done" : "❌ error";
    }
}
