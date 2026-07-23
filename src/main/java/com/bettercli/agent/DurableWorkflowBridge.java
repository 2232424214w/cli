package com.bettercli.agent;

import com.bettercli.runtime.task.TaskRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * DurableTask ↔ WorkflowRuntime 桥接：一个 durable task = 一次 workflow run，
 * 每步写 {@link WorkflowCheckpointStore}，崩溃重入队后从断点续跑。
 *
 * <p>{@link TaskRunner#run(String, String)} 的 taskId 作为 checkpoint runId。
 */
public class DurableWorkflowBridge implements TaskRunner {

    private final Function<String, WorkflowScript> scriptFactory;
    private final WorkflowCheckpointStore checkpointStore;
    private final Function<String, String> snapshotHook;

    /**
     * @param scriptFactory  根据 prompt 构造脚本（可注入 LLM 节点或纯函数）
     * @param checkpointDir  检查点目录
     * @param snapshotHook   可选：每步后创建 Side-Git 快照，返回 snapshotId；null 则跳过
     */
    public DurableWorkflowBridge(Function<String, WorkflowScript> scriptFactory,
                                 Path checkpointDir,
                                 Function<String, String> snapshotHook) {
        this.scriptFactory = scriptFactory;
        this.checkpointStore = new WorkflowCheckpointStore(checkpointDir);
        this.snapshotHook = snapshotHook;
    }

    public DurableWorkflowBridge(Function<String, WorkflowScript> scriptFactory, Path checkpointDir) {
        this(scriptFactory, checkpointDir, null);
    }

    @Override
    public String run(String prompt) throws Exception {
        return run("anon_" + System.nanoTime(), prompt);
    }

    @Override
    public String run(String taskId, String prompt) throws Exception {
        WorkflowScript script = scriptFactory.apply(prompt);
        if (script == null) {
            throw new IllegalStateException("scriptFactory 返回 null");
        }
        SharedState state = new SharedState();
        state.setGoal(script.goal() == null || script.goal().isBlank() ? prompt : script.goal(), null);

        Optional<WorkflowCheckpoint> existing = checkpointStore.load(taskId);
        WorkflowRuntime runtime = new WorkflowRuntime().setRunId(taskId);
        if (existing.isPresent()) {
            WorkflowCheckpoint cp = existing.get();
            cp.restoreInto(state);
            runtime.withSkippedSteps(cp.executedIdSet());
        }

        AtomicInteger stepNo = new AtomicInteger();
        runtime.setCheckpointListener((st, executed) -> {
            try {
                String snap = null;
                if (snapshotHook != null) {
                    snap = snapshotHook.apply(taskId + "-step-" + stepNo.incrementAndGet());
                }
                checkpointStore.save(WorkflowCheckpoint.capture(taskId, st, executed, snap));
            } catch (Exception e) {
                throw new RuntimeException("保存 workflow checkpoint 失败: " + e.getMessage(), e);
            }
        });

        WorkflowScript.WorkflowResult result = runtime.execute(script, state);
        if (result.completed()) {
            try {
                checkpointStore.delete(taskId);
            } catch (Exception ignored) {
            }
            String gather = state.getArtifact("gather");
            if (gather != null && !gather.isBlank()) {
                return gather;
            }
            // 取最后一个有产物的 step
            List<String> ids = result.executedStepIds();
            for (int i = ids.size() - 1; i >= 0; i--) {
                String a = state.getArtifact(ids.get(i));
                if (a != null && !a.isBlank()) {
                    return a;
                }
            }
            return result.summary();
        }
        throw new IllegalStateException(result.summary());
    }

    public WorkflowCheckpointStore checkpointStore() {
        return checkpointStore;
    }
}
