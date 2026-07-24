package com.bettercli.hitl;

import java.util.List;

/**
 * Agent 主动反问用户的请求（与审批语义分离）。
 *
 * <p>options 为空 = 自由问答；非空则渲染为编号选项。
 * defaultAnswer 供非交互通道（微信等）降级使用。
 */
public record ClarificationRequest(String question, List<String> options, String defaultAnswer) {

    public static final String NON_INTERACTIVE_FALLBACK =
            "当前通道无法向用户提问，请基于已有信息继续或如实说明无法完成";

    public ClarificationRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question required");
        }
        question = question.trim();
        options = options == null ? List.of() : List.copyOf(options);
        defaultAnswer = defaultAnswer == null ? "" : defaultAnswer;
    }

    public static ClarificationRequest of(String question) {
        return new ClarificationRequest(question, List.of(), "");
    }

    public static ClarificationRequest of(String question, List<String> options) {
        return new ClarificationRequest(question, options, "");
    }

    /** 非交互通道解析最终答案：优先 defaultAnswer，否则固定降级文案。 */
    public String resolveNonInteractiveAnswer() {
        if (!defaultAnswer.isBlank()) {
            return defaultAnswer;
        }
        return NON_INTERACTIVE_FALLBACK;
    }

    public boolean hasOptions() {
        return !options.isEmpty();
    }
}
