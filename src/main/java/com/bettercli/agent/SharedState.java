package com.bettercli.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multi-Agent 共享黑板（对标 2026 Blackboard 架构 + 状态所有权契约）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>显式共享状态</b>：替代原 {@code buildStepContext} 的隐式状态传递。
 *       worker/reviewer 产物双写进黑板，后续阶段（p2p / workflow）可直接读黑板而不再
 *       依赖 orchestrator 中转。</li>
 *   <li><b>字段所有权契约</b>：每个字段只有特定角色能写，越权写入抛
 *       {@link StateOwnershipException}（防御性，防 agent 互相覆盖产物）。
 *       <pre>
 *       goal            -> orchestrator（用 null role 表示）
 *       plan            -> PLANNER
 *       artifacts.&lt;id&gt;  -> 执行该 step 的 WORKER
 *       reviews.&lt;id&gt;    -> REVIEWER
 *       routingLog      -> orchestrator（派活决策，可审计）
 *       </pre>
 *       所有角色可读。</li>
 *   <li><b>routing 审计</b>：orchestrator 每次派活记录 {@link RoutingDecision}，
 *       对标 2026"supervisor routing reasoning 写进 state history"最佳实践。</li>
 *   <li><b>线程安全</b>：并行批次多 worker 同时写各自 stepId 的 artifact，
 *       用 ConcurrentHashMap + CopyOnWriteArrayList。</li>
 * </ul>
 */
public class SharedState {

    /** 所有权违规异常（防御性，防 agent 越权覆盖彼此产物）。 */
    public static final class StateOwnershipException extends RuntimeException {
        public StateOwnershipException(String msg) { super(msg); }
    }

    /** orchestrator 派活决策记录（可审计）。 */
    public record RoutingDecision(String stepId, String assignee, String reason, long timestamp) {
        public RoutingDecision {
            if (stepId == null) stepId = "";
            if (assignee == null) assignee = "";
            if (reason == null) reason = "";
        }
    }

    private String goal;
    private String plan;
    private final Map<String, String> artifacts = new ConcurrentHashMap<>();
    private final Map<String, String> reviews = new ConcurrentHashMap<>();
    private final List<RoutingDecision> routingLog = new CopyOnWriteArrayList<>();
    // peer-to-peer 留言通道（对标 Claude Code agent teams：worker 间直接消息）。
    // 任何 worker 可向另一 worker 发消息（异步留言），对方下次执行前由 orchestrator 注入 inbox。
    private final List<PeerMessage> peerMessages = new CopyOnWriteArrayList<>();

    /** peer 间留言（异步，存黑板，对方下次执行前读取）。 */
    public record PeerMessage(String from, String to, String content, long timestamp) {
        public PeerMessage {
            if (from == null) from = "";
            if (to == null) to = "";
            if (content == null) content = "";
        }
    }

    /** orchestrator 写 goal（用 null role 标识 orchestrator）。 */
    public void setGoal(String goal, AgentRole writer) {
        enforce(writer == null, "goal", "orchestrator");
        this.goal = goal;
    }

    /** PLANNER 写 plan。 */
    public void setPlan(String plan, AgentRole writer) {
        enforce(writer == AgentRole.PLANNER, "plan", "PLANNER");
        this.plan = plan;
    }

    /** WORKER 写自己 step 的 artifact。 */
    public void putArtifact(String stepId, String result, AgentRole writer) {
        enforce(writer == AgentRole.WORKER, "artifacts[" + stepId + "]", "WORKER");
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        artifacts.put(stepId, result == null ? "" : result);
    }

    /**
     * WorkflowRuntime 可信写入 artifact（阶段E Dynamic Workflow）。
     * runtime 代表系统执行 TaskStep，不是某个 agent 角色，故绕过所有权校验。
     * 仅 {@link WorkflowRuntime} 应调用此方法。
     */
    public void putArtifactByRuntime(String stepId, String result) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        artifacts.put(stepId, result == null ? "" : result);
    }

    /** REVIEWER 写自己 step 的 review。 */
    public void putReview(String stepId, String review, AgentRole writer) {
        enforce(writer == AgentRole.REVIEWER, "reviews[" + stepId + "]", "REVIEWER");
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId 不能为空");
        }
        reviews.put(stepId, review == null ? "" : review);
    }

    /** orchestrator 记录派活决策（对标 2026 routing 审计）。 */
    public void recordRouting(String stepId, String assignee, String reason) {
        routingLog.add(new RoutingDecision(stepId, assignee, reason, System.currentTimeMillis()));
    }

    /** worker 向另一 worker 发留言（p2p，异步存黑板）。空 to 表示广播给所有 worker。 */
    public void postPeerMessage(String from, String to, String content) {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("from 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        peerMessages.add(new PeerMessage(from.trim(), to == null ? "" : to.trim(),
                content, System.currentTimeMillis()));
    }

    /** 读取发给某 worker 的 inbox（含广播消息），按时间顺序，不可变。 */
    public List<PeerMessage> getInbox(String workerName) {
        if (workerName == null || workerName.isBlank()) {
            return List.of();
        }
        String w = workerName.trim();
        return peerMessages.stream()
                .filter(m -> m.to().isEmpty() || m.to().equals(w))
                .filter(m -> !m.from().equals(w)) // 不返回自己发的
                .toList();
    }

    /** 全部 peer 消息（不可变，供测试/审计）。 */
    public List<PeerMessage> getPeerMessages() {
        return List.copyOf(peerMessages);
    }

    public String getGoal() { return goal; }
    public String getPlan() { return plan; }
    public String getArtifact(String stepId) { return artifacts.get(stepId); }
    public String getReview(String stepId) { return reviews.get(stepId); }
    public Map<String, String> snapshotArtifacts() { return Collections.unmodifiableMap(artifacts); }
    public Map<String, String> snapshotReviews() { return Collections.unmodifiableMap(reviews); }

    /** 不可变 routing 决策日志（供测试断言 / 审计）。 */
    public List<RoutingDecision> getRoutingLog() {
        return List.copyOf(routingLog);
    }

    public boolean isEmpty() {
        return goal == null && plan == null && artifacts.isEmpty() && reviews.isEmpty()
                && routingLog.isEmpty() && peerMessages.isEmpty();
    }

    private static void enforce(boolean ok, String field, String owner) {
        if (!ok) {
            throw new StateOwnershipException(
                    "SharedState 字段 " + field + " 所有权违规：仅 " + owner + " 可写");
        }
    }
}
