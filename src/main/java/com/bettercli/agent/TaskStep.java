package com.bettercli.agent;

import java.util.function.Function;

/**
 * 顺序任务步骤：执行 {@code action}，产物以 {@code id} 为 key 写入 {@link SharedState} 黑板。
 *
 * @param id          步骤 id，同时是黑板 artifact key
 * @param description 人类可读描述（供审计/展示，不参与执行）
 * @param action      读黑板入参，返回产物字符串。实际使用时把 SubAgent.execute 包成此函数；
 *                    测试时注入纯函数，使 WorkflowRuntime 可独立单测。
 */
public record TaskStep(String id, String description, Function<SharedState, String> action) implements WorkflowStep {
    public TaskStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TaskStep id 不能为空");
        }
        if (action == null) {
            throw new IllegalArgumentException("TaskStep action 不能为空");
        }
    }
}
