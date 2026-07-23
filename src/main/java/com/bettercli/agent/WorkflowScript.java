package com.bettercli.agent;

import java.util.List;
import java.util.function.Function;

/**
 * 可执行编排脚本（对标 Claude Code 2026.6 Dynamic Workflow：AI 写脚本来编排 agent）。
 *
 * <p>与 {@code plan/ExecutionPlan} 的静态 DAG 的质变：
 * <ul>
 *   <li><b>带控制流</b>：支持顺序 / 并行 / 条件 / 循环，不只是拓扑序。</li>
 *   <li><b>脚本驱动</b>：LLM 只参与"生成脚本"一次，执行阶段由 {@link WorkflowRuntime}
 *       驱动，不再每步调 LLM；中间结果存 {@link SharedState} 黑板，不回灌 LLM context。</li>
 *   <li><b>可扩到上千步</b>：因为执行不依赖 LLM 逐轮决策，context 不会随步数膨胀。</li>
 * </ul>
 *
 * <p>{@code TaskStep.action} 是 {@code Function<SharedState, String>}：执行时读黑板入参、
 * 返回产物写黑板。实际使用时把 SubAgent.execute 包成此函数注入；测试时注入纯函数，
 * 使 {@link WorkflowRuntime} 可独立单测，不依赖 LLM。
 */
public record WorkflowScript(String goal, List<WorkflowStep> steps) {

    /**
     * 条件：读黑板 artifact[ref]，与 equals 字符串比较。
     * 简单相等语义，避免引入表达式引擎；满足 90% 编排场景（如 "test_passed" == "true"）。
     */
    public record Condition(String artifactRef, String equals) {
        public boolean evaluate(SharedState state) {
            if (state == null) return false;
            String actual = state.getArtifact(artifactRef);
            return equals == null ? actual == null : equals.equals(actual);
        }
    }

    /** 执行结果。 */
    public record WorkflowResult(boolean completed, String summary, List<String> executedStepIds) {
        public static WorkflowResult ok(String summary, List<String> ids) {
            return new WorkflowResult(true, summary, ids);
        }

        public static WorkflowResult aborted(String summary, List<String> ids) {
            return new WorkflowResult(false, summary, ids);
        }
    }
}
