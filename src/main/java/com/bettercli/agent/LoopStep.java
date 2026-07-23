package com.bettercli.agent;

import java.util.List;

/**
 * 循环步骤：重复执行 body 直到 {@link WorkflowScript.Condition} 满足或达到 maxIterations（防死循环）。
 *
 * <p>对标 2026 生产硬性最佳实践：循环必须有硬上限（iteration counter / recursion_limit），
 * 防止 LLM 写出无限循环脚本。maxIterations 默认建议 ≤ 25。
 */
public record LoopStep(String id, WorkflowScript.Condition condition, int maxIterations,
                       List<WorkflowStep> body) implements WorkflowStep {
    public LoopStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("LoopStep id 不能为空");
        }
        if (condition == null) {
            throw new IllegalArgumentException("LoopStep condition 不能为空");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("LoopStep maxIterations 必须 > 0");
        }
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("LoopStep body 不能为空");
        }
        body = List.copyOf(body);
    }
}
