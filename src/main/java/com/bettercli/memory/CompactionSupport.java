package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 主轨压缩的共享小工具：溢出识别、schema 估算、切割边界定位。
 * 供 Agent / PlanExecuteAgent / SubAgent 共用，避免三处复制分叉。
 */
public final class CompactionSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompactionSupport() {
    }

    /** 从异常识别 API 兜底触发点；无法识别时返回 null。 */
    public static CompactTrigger overflowTrigger(Throwable error) {
        if (ConversationHistoryCompactor.looksLikeContextWindowExceeded(error)) {
            return CompactTrigger.CONTEXT_WINDOW_EXCEEDED;
        }
        if (ConversationHistoryCompactor.looksLikePromptTooLong(error)) {
            return CompactTrigger.PROMPT_TOO_LONG;
        }
        return null;
    }

    /** 估算工具 Schema 占用（不含消息体）。失败返回 0。 */
    public static int estimateToolsSchemaTokens(Object toolDefinitions) {
        if (toolDefinitions == null) {
            return 0;
        }
        try {
            String json = toolDefinitions instanceof String s
                    ? s
                    : MAPPER.writeValueAsString(toolDefinitions);
            return MemoryEntry.estimateTokens(json);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 从后往前找最后一条真实用户消息下标；找不到返回 -1。 */
    public static int findLastUserIndex(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            return -1;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if (ConversationHistoryCompactor.isRealUserMessage(history.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
