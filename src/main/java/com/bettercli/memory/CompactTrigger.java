package com.bettercli.memory;

/**
 * 上下文压缩触发点（对标 1024 Context Checkpoint Compaction）。
 */
public enum CompactTrigger {
    /** 本轮首次 LLM 调用前：只压「当前用户消息」之前的历史 */
    PRE_TURN,
    /** 本轮工具执行完毕后：全量压缩，保留段为空 */
    MID_TURN,
    /** HTTP/API 层 prompt 过长异常兜底 */
    PROMPT_TOO_LONG,
    /** 模型侧确认上下文窗口已打满 */
    CONTEXT_WINDOW_EXCEEDED,
    /** 用户手动 /compact */
    MANUAL
}
