package com.bettercli.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Workflow 脚本执行器（对标 Claude Code 2026.6 Dynamic Workflow runtime）。
 *
 * <p>核心特征：
 * <ul>
 *   <li><b>脚本驱动</b>：执行 {@link WorkflowScript}；{@link TaskStep#action} 由调用方注入
 *       （纯函数或 {@link WorkflowAdapters} LLM 节点）。</li>
 *   <li><b>中间结果存黑板</b>：产物以 step id 为 key 写入 {@link SharedState}。</li>
 *   <li><b>控制流</b>：顺序 / 并行 / 条件 / 循环（maxIterations 硬上限）。</li>
 *   <li><b>断点续跑</b>：可注入已执行 step id 集合跳过已完成步骤；每步完成后可选
 *       checkpoint listener 持久化检查点（阶段 3 durable）。</li>
 * </ul>
 */
public class WorkflowRuntime {

    private static final int PARALLEL_TIMEOUT_SECONDS = 120;

    private Set<String> skipStepIds = Set.of();
    private BiConsumer<SharedState, List<String>> checkpointListener;
    private String runId = "";

    /** 设置续跑时已完成的步骤 id（这些 TaskStep 将被跳过，黑板应已恢复对应 artifact）。 */
    public WorkflowRuntime withSkippedSteps(Set<String> skipped) {
        this.skipStepIds = skipped == null ? Set.of() : Set.copyOf(skipped);
        return this;
    }

    /** 每成功执行一个新 TaskStep（非跳过）后回调，参数为当前黑板与累计 executedStepIds。 */
    public WorkflowRuntime setCheckpointListener(BiConsumer<SharedState, List<String>> listener) {
        this.checkpointListener = listener;
        return this;
    }

    public WorkflowRuntime setRunId(String runId) {
        this.runId = runId == null ? "" : runId;
        return this;
    }

    public String runId() {
        return runId;
    }

    /**
     * 执行脚本。中间结果写入给定 {@code state}（黑板），返回执行结果。
     * 任意步骤抛异常则中止整条脚本，已写入黑板的产物保留。
     */
    public WorkflowScript.WorkflowResult execute(WorkflowScript script, SharedState state) {
        if (script == null) {
            return WorkflowScript.WorkflowResult.aborted("脚本为空", List.of());
        }
        if (state == null) {
            return WorkflowScript.WorkflowResult.aborted("黑板未初始化", List.of());
        }
        List<String> executed = new ArrayList<>();
        // 续跑：把已跳过的 id 计入 executed，保持轨迹完整
        executed.addAll(skipStepIds);
        try {
            for (WorkflowStep step : script.steps()) {
                executeStep(step, state, executed);
            }
            return WorkflowScript.WorkflowResult.ok("脚本执行完成: " + script.goal(), executed);
        } catch (RuntimeException e) {
            return WorkflowScript.WorkflowResult.aborted("脚本中止: " + e.getMessage(), executed);
        }
    }

    private void executeStep(WorkflowStep step, SharedState state, List<String> executed) {
        if (step instanceof TaskStep t) {
            executeTask(t, state, executed);
        } else if (step instanceof ParallelStep p) {
            executeParallel(p, state, executed);
        } else if (step instanceof ConditionalStep c) {
            executeConditional(c, state, executed);
        } else if (step instanceof LoopStep l) {
            executeLoop(l, state, executed);
        } else {
            throw new IllegalStateException("未知步骤类型: " + step.getClass());
        }
    }

    /** 顺序任务：已在 skip 集合中则跳过；否则执行 action 并触发 checkpoint。 */
    private void executeTask(TaskStep t, SharedState state, List<String> executed) {
        if (skipStepIds.contains(t.id())) {
            if (!executed.contains(t.id())) {
                executed.add(t.id());
            }
            state.recordRouting(t.id(), "runtime", "checkpoint 跳过（已完成）");
            return;
        }
        String result = t.action().apply(state);
        state.putArtifactByRuntime(t.id(), result == null ? "" : result);
        executed.add(t.id());
        state.recordRouting(t.id(), "runtime", "workflow task 执行");
        fireCheckpoint(state, executed);
    }

    /** 并行：所有分支同时执行；已完成的分支在 TaskStep 层跳过。 */
    private void executeParallel(ParallelStep p, SharedState state, List<String> executed) {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(1, p.branches().size()), r -> {
            Thread th = new Thread(r, "bettercli-workflow-parallel");
            th.setDaemon(true);
            return th;
        });
        List<Future<?>> futures = new ArrayList<>();
        List<String> branchExecuted = java.util.Collections.synchronizedList(new ArrayList<>());
        for (WorkflowStep branch : p.branches()) {
            futures.add(executor.submit(() -> executeStep(branch, state, branchExecuted)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                executor.shutdownNow();
                throw new RuntimeException("并行分支失败: " + e.getMessage(), e);
            }
        }
        executor.shutdown();
        awaitTermination(executor);
        for (String id : branchExecuted) {
            if (!executed.contains(id)) {
                executed.add(id);
            }
        }
        if (!executed.contains(p.id())) {
            executed.add(p.id());
        }
        fireCheckpoint(state, executed);
    }

    private void executeConditional(ConditionalStep c, SharedState state, List<String> executed) {
        boolean ok = c.condition().evaluate(state);
        List<WorkflowStep> branch = ok ? c.thenSteps() : c.elseSteps();
        for (WorkflowStep s : branch) {
            executeStep(s, state, executed);
        }
        if (!executed.contains(c.id())) {
            executed.add(c.id());
        }
    }

    private void executeLoop(LoopStep l, SharedState state, List<String> executed) {
        int iter = 0;
        while (iter < l.maxIterations()) {
            if (l.condition().evaluate(state)) {
                break;
            }
            iter++;
            for (WorkflowStep s : l.body()) {
                executeStep(s, state, executed);
            }
        }
        if (iter >= l.maxIterations() && !l.condition().evaluate(state)) {
            throw new RuntimeException("LoopStep " + l.id() + " 达到 maxIterations=" + l.maxIterations());
        }
        if (!executed.contains(l.id())) {
            executed.add(l.id());
        }
    }

    private void fireCheckpoint(SharedState state, List<String> executed) {
        if (checkpointListener != null) {
            checkpointListener.accept(state, List.copyOf(executed));
        }
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
