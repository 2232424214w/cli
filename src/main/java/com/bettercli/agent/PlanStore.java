package com.bettercli.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 会话级规划存储（对标 Claude Code TodoWrite 的内存态）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>replace 语义：每次 {@link #replace(List)} 整体覆盖，模型不需要做增量 diff，
 *       避免漏状态。对标 Claude Code TodoWrite 的 write 模式。</li>
 *   <li>id 稳定：模型可自带 id；缺省时由 store 自增分配，保证 checkbox 视图序号稳定。</li>
 *   <li>线程安全：ReAct loop 单线程驱动，但工具执行可能并发读 snapshot，
 *       故用 volatile + 防复制快照。</li>
 *   <li>不持久化：会话结束即丢弃；落盘 .paicli/plan.md 由 Agent 层可选触发，
 *       store 本身只管内存。</li>
 * </ul>
 */
public class PlanStore {

    private final AtomicInteger idSeq = new AtomicInteger(0);
    private volatile List<ReActPlan> plans = Collections.emptyList();

    /**
     * 整体替换当前 plan。
     *
     * <p>入参允许 null/空（等价于清空）。每条若 id 为空/空白则自动分配 "1"/"2"/...。
     * 重复 id 会被去重保留首次出现，避免 checkbox 视图错位。
     */
    public synchronized void replace(List<ReActPlan> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            plans = Collections.emptyList();
            return;
        }
        List<ReActPlan> normalized = new ArrayList<>(incoming.size());
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (ReActPlan p : incoming) {
            if (p == null) {
                continue;
            }
            String id = (p.id() == null || p.id().isBlank())
                    ? String.valueOf(idSeq.incrementAndGet())
                    : p.id().trim();
            if (!seenIds.add(id)) {
                continue; // 去重
            }
            normalized.add(new ReActPlan(id, p.content(), p.status(), p.updatedAt()));
        }
        plans = Collections.unmodifiableList(normalized);
    }

    /** 返回不可变快照，供渲染/工具返回值使用。 */
    public List<ReActPlan> snapshot() {
        return plans;
    }

    public boolean isEmpty() {
        return plans.isEmpty();
    }

    public int size() {
        return plans.size();
    }

    /** 已完成数（用于进度摘要）。 */
    public long completedCount() {
        return plans.stream().filter(p -> p.status() == ReActPlan.Status.COMPLETED).count();
    }

    public void clear() {
        plans = Collections.emptyList();
    }

    /**
     * 渲染为 checkbox 视图（对标 Claude Code TodoWrite 输出）。
     * 例：
     * <pre>
     * 当前计划（2/4）:
     *   ☐ 读取 auth 模块
     *   ■ 重构 token 校验
     *   ☐ 补测试
     * </pre>
     */
    public String formatView() {
        List<ReActPlan> snap = plans;
        if (snap.isEmpty()) {
            return "（当前没有计划）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前计划（").append(completedCount()).append('/').append(snap.size()).append("）:");
        for (ReActPlan p : snap) {
            sb.append("\n  ").append(checkbox(p.status())).append(' ').append(p.content());
        }
        return sb.toString();
    }

    private static String checkbox(ReActPlan.Status s) {
        return switch (s) {
            case PENDING -> "☐";
            case IN_PROGRESS -> "◑";
            case COMPLETED -> "■";
        };
    }
}
