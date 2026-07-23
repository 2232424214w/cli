package com.bettercli.agent;

import java.util.List;

/**
 * 混编 worker 池：统一调度本地 {@link SubAgent} 与远程 {@code com.bettercli.a2a.RemoteAgent}。
 *
 * <p>对标 2026 A2A + Claude Code agent teams 共识：worker 池应能混编本地与远程 agent。
 * 本类是 {@link Worker} 接口的轻量调度器，证明本地+远程可统一派活；后续 {@code AgentOrchestrator}
 * 的 worker 池可从 {@code List<SubAgent>} 迁移到 {@code List<Worker>} + 本调度器。
 *
 * <p>调度策略与 {@code AgentOrchestrator.pickWorker} 对齐：按名指派优先，否则游标轮询。
 */
public class MixedWorkerPool {

    private final List<Worker> workers;

    public MixedWorkerPool(List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("worker 池不能为空");
        }
        this.workers = List.copyOf(workers);
    }

    public List<Worker> workers() {
        return workers;
    }

    public int size() {
        return workers.size();
    }

    /** 是否含远程 worker（按全限定类名判断，避免 agent 包反向依赖 a2a 包）。 */
    public boolean hasRemote() {
        return workers.stream().anyMatch(w -> w.getClass().getName().contains("RemoteAgent"));
    }

    public boolean hasLocal() {
        return workers.stream().anyMatch(w -> w instanceof SubAgent);
    }

    /** 按名查找 worker（与 orchestrator.normalizeAssignee 对齐：找不到返回 null）。 */
    public Worker find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return workers.stream()
                .filter(w -> trimmed.equals(w.getName()))
                .findFirst()
                .orElse(null);
    }

    /** 游标轮询（与 orchestrator.pickWorker 回退路径对齐）。 */
    public Worker roundRobin(int cursor) {
        return workers.get(((cursor % workers.size()) + workers.size()) % workers.size());
    }

    /** 派活：名指派优先，否则轮询。返回 worker + 决策原因（供审计）。 */
    public Routing pick(String assignee, int cursor) {
        Worker named = find(assignee);
        if (named != null) {
            return new Routing(named, "规划者指派 " + assignee);
        }
        Worker picked = roundRobin(cursor);
        String reason = assignee != null && !assignee.isBlank()
                ? "规划者指派的 " + assignee + " 不存在，回退轮询"
                : "未指派，按游标轮询";
        return new Routing(picked, reason);
    }

    /** 派活结果（worker + 原因）。 */
    public record Routing(Worker worker, String reason) {}
}
