package com.bettercli.agent;

import java.util.List;

/**
 * 条件步骤：求值 {@link WorkflowScript.Condition}（读黑板 artifact），为真执行 thenSteps，否则 elseSteps。
 * 允许 thenSteps/elseSteps 为空（相当于 if / if-else）。
 */
public record ConditionalStep(String id, WorkflowScript.Condition condition,
                              List<WorkflowStep> thenSteps,
                              List<WorkflowStep> elseSteps) implements WorkflowStep {
    public ConditionalStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ConditionalStep id 不能为空");
        }
        if (condition == null) {
            throw new IllegalArgumentException("ConditionalStep condition 不能为空");
        }
        thenSteps = thenSteps == null ? List.of() : List.copyOf(thenSteps);
        elseSteps = elseSteps == null ? List.of() : List.copyOf(elseSteps);
    }
}
