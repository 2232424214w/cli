package com.bettercli.runtime.task;

/**
 * 后台任务完成/失败时的主动回推钩子（微信 / HTTP SSE / 其他前端可插拔）。
 * DurableTaskManager 在任务进入终态时调用；默认空实现不打断主路径。
 */
@FunctionalInterface
public interface TaskCompletionListener {
    void onTerminal(DurableTask task);

    TaskCompletionListener NO_OP = task -> {};
}
