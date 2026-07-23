package com.bettercli.agent;

import java.util.List;

/**
 * 并行步骤：所有分支同时执行（fan-out），全部完成后才继续下一步。
 * 分支间产物按各自 step id 写黑板；分支间不应写同一 key（所有权契约会防覆盖）。
 */
public record ParallelStep(String id, List<WorkflowStep> branches) implements WorkflowStep {
    public ParallelStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ParallelStep id 不能为空");
        }
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("ParallelStep branches 不能为空");
        }
        branches = List.copyOf(branches);
    }
}
