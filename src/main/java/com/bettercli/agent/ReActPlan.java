package com.bettercli.agent;

/**
 * ReAct 轻量规划条目（对标 Claude Code TodoWrite）。
 * 与 plan/Task.java 的 DAG 节点不同：这是会话级内存态的简单 todo，
 * 不带依赖图、不带执行结果，仅用于在 ReAct loop 中追踪多步任务进度。
 *
 * @param id        稳定 id（由模型给出或 store 自动分配，序号语义）
 * @param content   任务描述
 * @param status    pending / in_progress / completed
 * @param updatedAt 更新时间戳（millis）
 */
public record ReActPlan(String id, String content, Status status, long updatedAt) {

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED
    }

    /** 创建一条 pending 的新条目，updatedAt 取当前时间。 */
    public static ReActPlan of(String id, String content) {
        return new ReActPlan(id, content, Status.PENDING, System.currentTimeMillis());
    }

    public ReActPlan withStatus(Status newStatus) {
        return new ReActPlan(id, content, newStatus, System.currentTimeMillis());
    }
}
