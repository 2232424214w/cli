package com.bettercli.agent;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scatter-Gather（fan-out/fan-in）并行调研编排。
 *
 * <p>区别于 Multi-Agent 里「无依赖 step 并行」（不同子任务碰巧无依赖）：
 * 这里是<strong>为同一目标派 N 路 worker 并行探索不同子角度，再由汇总节点一次 LLM 合成</strong>。
 *
 * <p>底层复用 {@link ParallelStep} + {@link WorkflowAdapters#fanInTask}，经 {@link WorkflowRuntime} 执行，
 * 中间产物存 {@link SharedState} 黑板。
 */
public final class ScatterGather {

    private ScatterGather() {}

    /**
     * 构造 scatter-gather 脚本：每个 angle 一个 LLM TaskStep（fan-out），最后 fan-in 汇总。
     *
     * @param goal          总目标（写入脚本 goal 与黑板）
     * @param angles        子角度描述列表（至少 2 个）
     * @param workers       执行调研的 Worker 池（按序轮询分配给各 angle；可少于 angles）
     * @param synthesizer   负责 fan-in 合成的 Worker
     * @param out           流式输出；null 则 System.out
     */
    public static WorkflowScript build(String goal,
                                       List<String> angles,
                                       List<? extends Worker> workers,
                                       Worker synthesizer,
                                       PrintStream out) {
        Objects.requireNonNull(angles, "angles");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(synthesizer, "synthesizer");
        if (angles.size() < 2) {
            throw new IllegalArgumentException("scatter-gather 至少需要 2 个子角度");
        }
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("workers 不能为空");
        }

        List<WorkflowStep> branches = new ArrayList<>();
        List<String> artifactKeys = new ArrayList<>();
        for (int i = 0; i < angles.size(); i++) {
            String angle = angles.get(i);
            if (angle == null || angle.isBlank()) {
                throw new IllegalArgumentException("angle[" + i + "] 不能为空");
            }
            String id = "angle_" + (i + 1);
            artifactKeys.add(id);
            Worker w = workers.get(i % workers.size());
            String task = "调研子角度：" + angle.trim()
                    + "\n（总目标：" + (goal == null ? "" : goal) + "）\n"
                    + "请聚焦该角度深入分析，给出独立结论，不要试图覆盖其他角度。";
            branches.add(new TaskStep(id, angle.trim(),
                    WorkflowAdapters.subAgentAction(w, task, out)));
        }

        String synthGoal = "综合 " + angles.size() + " 路并行调研，对齐冲突、去重、提炼最终结论。"
                + (goal == null || goal.isBlank() ? "" : " 总目标：" + goal);
        TaskStep gather = WorkflowAdapters.fanInTask(
                "gather", synthGoal, synthesizer, artifactKeys, out);

        return new WorkflowScript(
                goal == null ? "scatter-gather" : goal,
                List.of(new ParallelStep("fanout", branches), gather));
    }

    /**
     * 构造并执行：初始化黑板 goal，跑完返回结果。
     */
    public static WorkflowScript.WorkflowResult explore(String goal,
                                                        List<String> angles,
                                                        List<? extends Worker> workers,
                                                        Worker synthesizer,
                                                        SharedState state,
                                                        PrintStream out) {
        WorkflowScript script = build(goal, angles, workers, synthesizer, out);
        SharedState board = state == null ? new SharedState() : state;
        if (board.getGoal() == null) {
            board.setGoal(goal, null);
        }
        return new WorkflowRuntime().execute(script, board);
    }
}
