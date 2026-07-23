package com.bettercli.runtime.task;

@FunctionalInterface
public interface TaskRunner {
    String run(String prompt) throws Exception;

    /**
     * 带 taskId 的执行入口（供 checkpoint 断点续跑 / 审计）。
     * 默认忽略 id，委托 {@link #run(String)}，保持现有 lambda 兼容。
     */
    default String run(String taskId, String prompt) throws Exception {
        return run(prompt);
    }
}
