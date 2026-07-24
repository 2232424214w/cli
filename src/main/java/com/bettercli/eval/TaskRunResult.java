package com.bettercli.eval;

import java.util.List;

/**
 * 单条黄金任务跑完后的结果：是否成功、判分细节、token/轮次/耗时。
 */
public record TaskRunResult(
        String taskId,
        String mode,
        boolean success,
        List<String> scoreDetails,
        String agentOutput,
        int llmCalls,
        int iterationsEstimate,
        int inputTokens,
        int outputTokens,
        long elapsedMillis,
        String error
) {
    public TaskRunResult {
        scoreDetails = scoreDetails == null ? List.of() : List.copyOf(scoreDetails);
        agentOutput = agentOutput == null ? "" : agentOutput;
        error = error == null ? "" : error;
    }
}
