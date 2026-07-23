package com.bettercli.agent;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Workflow ↔ SubAgent / Worker 胶水（阶段 1：让 {@link WorkflowRuntime} 节点真正调 LLM）。
 *
 * <p>{@link WorkflowRuntime} 的 {@link TaskStep#action} 是 {@code Function<SharedState, String>}，
 * 本身不感知 LLM。本类把 {@link Worker#executeWithContext} 包成该函数，使脚本节点成为
 * 真正的 LLM 调用；并提供 fan-in 汇总语义（读黑板多个 artifact → 一次 LLM 合成）。
 *
 * <p>产物写入由 {@link WorkflowRuntime} 通过 {@link SharedState#putArtifactByRuntime} 完成，
 * action 只需返回字符串。
 */
public final class WorkflowAdapters {

    private WorkflowAdapters() {}

    /**
     * 把 Worker 执行包装为 TaskStep.action：读黑板 goal 作上下文，调 LLM，返回结果文本。
     *
     * @param worker      本地 SubAgent 或远程 RemoteAgent（{@link Worker}）
     * @param taskDescription 本步任务描述（注入为 AgentMessage content）
     * @param out         流式输出目标；null 则用 {@link System#out}
     */
    public static Function<SharedState, String> subAgentAction(Worker worker,
                                                              String taskDescription,
                                                              PrintStream out) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(taskDescription, "taskDescription");
        PrintStream target = out == null ? System.out : out;
        return state -> {
            String context = buildWorkerContext(state);
            AgentMessage task = AgentMessage.task("workflow", taskDescription);
            AgentMessage result = worker.executeWithContext(task, context, target);
            if (result == null) {
                return "";
            }
            if (result.type() == AgentMessage.Type.ERROR) {
                throw new RuntimeException("Workflow LLM 节点失败 [" + worker.getName() + "]: "
                        + result.content());
            }
            return result.content() == null ? "" : result.content();
        };
    }

    /**
     * Fan-in 汇总节点：从黑板读取多个 artifact，拼成 prompt，一次 LLM 调用合成。
     *
     * <p>区别于「无依赖 step 并行」：这是「为同一目标多角度探索后再合成」的 scatter-gather 语义。
     *
     * @param worker        负责合成的 Worker（通常用一个汇总角色的 SubAgent）
     * @param artifactKeys  要汇总的黑板 artifact key 列表（对应前序 fan-out 步骤 id）
     * @param synthesisGoal 合成目标说明（如「综合三路调研，给出最终结论」）
     * @param out           流式输出目标；null 则用 {@link System#out}
     */
    public static Function<SharedState, String> fanInAction(Worker worker,
                                                           List<String> artifactKeys,
                                                           String synthesisGoal,
                                                           PrintStream out) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(artifactKeys, "artifactKeys");
        if (artifactKeys.isEmpty()) {
            throw new IllegalArgumentException("fanInAction artifactKeys 不能为空");
        }
        PrintStream target = out == null ? System.out : out;
        String goal = synthesisGoal == null || synthesisGoal.isBlank()
                ? "综合以下各路结果，给出统一结论" : synthesisGoal;
        return state -> {
            StringBuilder prompt = new StringBuilder();
            prompt.append("合成目标：").append(goal).append("\n\n");
            prompt.append("各路调研 / 执行产物：\n");
            for (String key : artifactKeys) {
                String artifact = state == null ? null : state.getArtifact(key);
                prompt.append("--- [").append(key).append("] ---\n");
                if (artifact == null || artifact.isBlank()) {
                    prompt.append("（无产物）\n");
                } else {
                    prompt.append(artifact).append("\n");
                }
            }
            prompt.append("\n请综合以上内容，给出最终合成结果。不要简单拼接，要去重、对齐冲突、提炼结论。");

            AgentMessage task = AgentMessage.task("workflow-fan-in", prompt.toString());
            // fan-in 的上下文已全部在 task 里，不再重复注入 goal
            AgentMessage result = worker.executeWithContext(task, "", target);
            if (result == null) {
                return "";
            }
            if (result.type() == AgentMessage.Type.ERROR) {
                throw new RuntimeException("Workflow fan-in 节点失败 [" + worker.getName() + "]: "
                        + result.content());
            }
            return result.content() == null ? "" : result.content();
        };
    }

    /**
     * 便捷工厂：创建绑定 subAgentAction 的 {@link TaskStep}。
     */
    public static TaskStep llmTask(String id, String description, Worker worker, PrintStream out) {
        return new TaskStep(id, description, subAgentAction(worker, description, out));
    }

    /**
     * 便捷工厂：创建绑定 fanInAction 的 {@link TaskStep}。
     */
    public static TaskStep fanInTask(String id, String description, Worker worker,
                                     List<String> artifactKeys, PrintStream out) {
        return new TaskStep(id, description,
                fanInAction(worker, artifactKeys, description, out));
    }

    /** 从黑板构造注入 Worker 的上下文（goal + 已有 artifacts 摘要）。 */
    private static String buildWorkerContext(SharedState state) {
        if (state == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (state.getGoal() != null && !state.getGoal().isBlank()) {
            sb.append("总任务目标：").append(state.getGoal()).append("\n");
        }
        var artifacts = state.snapshotArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("黑板已有产物：\n");
            for (var e : artifacts.entrySet()) {
                String preview = e.getValue() == null ? "" : e.getValue();
                if (preview.length() > 300) {
                    preview = preview.substring(0, 300) + "...";
                }
                sb.append("- [").append(e.getKey()).append("] ").append(preview).append("\n");
            }
        }
        return sb.toString();
    }
}
