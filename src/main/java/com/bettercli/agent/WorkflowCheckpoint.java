package com.bettercli.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow 执行检查点：已执行步骤 + 黑板 artifacts（+ 可选 Side-Git 快照 id）。
 * 崩溃后可据此跳过已完成步骤、恢复黑板，实现断点续跑。
 */
public record WorkflowCheckpoint(
        String runId,
        String goal,
        List<String> executedStepIds,
        Map<String, String> artifacts,
        String snapshotId,
        long savedAtEpochMs
) {
    public WorkflowCheckpoint {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        executedStepIds = executedStepIds == null ? List.of() : List.copyOf(executedStepIds);
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        goal = goal == null ? "" : goal;
        snapshotId = snapshotId == null ? "" : snapshotId;
    }

    public Set<String> executedIdSet() {
        return Set.copyOf(executedStepIds);
    }

    /** 从当前黑板与已执行列表构造检查点。 */
    public static WorkflowCheckpoint capture(String runId, SharedState state,
                                             List<String> executed, String snapshotId) {
        Map<String, String> arts = new LinkedHashMap<>();
        if (state != null) {
            arts.putAll(state.snapshotArtifacts());
        }
        return new WorkflowCheckpoint(
                runId,
                state == null || state.getGoal() == null ? "" : state.getGoal(),
                executed == null ? List.of() : new ArrayList<>(executed),
                arts,
                snapshotId,
                System.currentTimeMillis()
        );
    }

    /** 把 artifacts / goal 灌回黑板（供续跑前恢复）。 */
    public void restoreInto(SharedState state) {
        if (state == null) {
            return;
        }
        if (state.getGoal() == null && goal != null && !goal.isBlank()) {
            state.setGoal(goal, null);
        }
        for (var e : artifacts.entrySet()) {
            state.putArtifactByRuntime(e.getKey(), e.getValue());
        }
    }
}
