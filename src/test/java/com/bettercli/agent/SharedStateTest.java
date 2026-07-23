package com.bettercli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SharedState（Multi-Agent 共享黑板）的字段所有权契约、产物存取与 routing 审计。
 * 对标 2026 Blackboard 架构 + 状态所有权最佳实践。
 */
class SharedStateTest {

    @Test
    void goalOnlyWritableByOrchestrator() {
        SharedState state = new SharedState();
        // orchestrator 用 null role
        assertDoesNotThrow(() -> state.setGoal("重构 auth", null));
        assertEquals("重构 auth", state.getGoal());
        // 其它角色写 goal 应被拒
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.setGoal("篡改", AgentRole.PLANNER));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.setGoal("篡改", AgentRole.WORKER));
    }

    @Test
    void planOnlyWritableByPlanner() {
        SharedState state = new SharedState();
        state.setPlan("{\"steps\":[]}", AgentRole.PLANNER);
        assertEquals("{\"steps\":[]}", state.getPlan());
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.setPlan("篡改", AgentRole.WORKER));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.setPlan("篡改", AgentRole.REVIEWER));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.setPlan("篡改", null));
    }

    @Test
    void artifactOnlyWritableByWorker() {
        SharedState state = new SharedState();
        state.putArtifact("s1", "产物1", AgentRole.WORKER);
        assertEquals("产物1", state.getArtifact("s1"));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.putArtifact("s1", "篡改", AgentRole.PLANNER));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.putArtifact("s1", "篡改", AgentRole.REVIEWER));
        // 黑板值不被越权写覆盖
        assertEquals("产物1", state.getArtifact("s1"));
    }

    @Test
    void reviewOnlyWritableByReviewer() {
        SharedState state = new SharedState();
        state.putReview("s1", "通过", AgentRole.REVIEWER);
        assertEquals("通过", state.getReview("s1"));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.putReview("s1", "篡改", AgentRole.WORKER));
        assertThrows(SharedState.StateOwnershipException.class,
                () -> state.putReview("s1", "篡改", AgentRole.PLANNER));
    }

    @Test
    void artifactNullResultStoredAsEmpty() {
        SharedState state = new SharedState();
        state.putArtifact("s1", null, AgentRole.WORKER);
        assertEquals("", state.getArtifact("s1"));
    }

    @Test
    void artifactRejectsBlankStepId() {
        SharedState state = new SharedState();
        assertThrows(IllegalArgumentException.class,
                () -> state.putArtifact(null, "x", AgentRole.WORKER));
        assertThrows(IllegalArgumentException.class,
                () -> state.putArtifact("  ", "x", AgentRole.WORKER));
    }

    @Test
    void missingArtifactReturnsNull() {
        SharedState state = new SharedState();
        assertNull(state.getArtifact("不存在"));
        assertNull(state.getReview("不存在"));
    }

    @Test
    void recordRoutingAppendsAuditEntry() {
        SharedState state = new SharedState();
        state.recordRouting("s1", "worker-1", "规划者指派 worker-1");
        state.recordRouting("s2", "worker-2", "并行批次派活");

        List<SharedState.RoutingDecision> log = state.getRoutingLog();
        assertEquals(2, log.size());
        assertEquals("s1", log.get(0).stepId());
        assertEquals("worker-1", log.get(0).assignee());
        assertTrue(log.get(0).reason().contains("规划者指派"));
        assertEquals("s2", log.get(1).stepId());
        assertTrue(log.get(1).timestamp() > 0);
    }

    @Test
    void routingLogIsImmutable() {
        SharedState state = new SharedState();
        state.recordRouting("s1", "worker-1", "test");
        List<SharedState.RoutingDecision> log = state.getRoutingLog();
        assertThrows(UnsupportedOperationException.class, () -> log.clear());
    }

    @Test
    void snapshotsAreUnmodifiable() {
        SharedState state = new SharedState();
        state.putArtifact("s1", "x", AgentRole.WORKER);
        state.putReview("s1", "y", AgentRole.REVIEWER);
        assertThrows(UnsupportedOperationException.class, () -> state.snapshotArtifacts().put("s2", "z"));
        assertThrows(UnsupportedOperationException.class, () -> state.snapshotReviews().put("s2", "z"));
    }

    @Test
    void isEmptyInitiallyAndAfterNoWrites() {
        SharedState state = new SharedState();
        assertTrue(state.isEmpty());
    }

    @Test
    void notEmptyAfterGoalSet() {
        SharedState state = new SharedState();
        state.setGoal("任务", null);
        assertTrue(!state.isEmpty());
    }

    // ===== peer-to-peer 留言通道（阶段D）=====

    @Test
    void postPeerMessageStoresAndReadsInbox() {
        SharedState state = new SharedState();
        state.postPeerMessage("worker-1", "worker-2", "你那边接口定了吗？");

        List<SharedState.PeerMessage> inbox = state.getInbox("worker-2");
        assertEquals(1, inbox.size());
        assertEquals("worker-1", inbox.get(0).from());
        assertEquals("worker-2", inbox.get(0).to());
        assertEquals("你那边接口定了吗？", inbox.get(0).content());
        assertTrue(inbox.get(0).timestamp() > 0);
    }

    @Test
    void broadcastMessageReachesAllWorkers() {
        SharedState state = new SharedState();
        state.postPeerMessage("worker-1", "", "全员注意：规范已更新");

        // 任何 worker 都应收到广播
        assertEquals(1, state.getInbox("worker-2").size());
        assertEquals(1, state.getInbox("worker-3").size());
        assertTrue(state.getInbox("worker-2").get(0).content().contains("规范已更新"));
    }

    @Test
    void inboxExcludesSelfMessages() {
        SharedState state = new SharedState();
        state.postPeerMessage("worker-1", "worker-1", "自言自语");
        state.postPeerMessage("worker-1", "worker-2", "给你");

        // worker-1 不应收到自己发给自己的留言
        assertTrue(state.getInbox("worker-1").isEmpty());
        // worker-2 收到
        assertEquals(1, state.getInbox("worker-2").size());
    }

    @Test
    void inboxReturnsEmptyForUnknownWorker() {
        SharedState state = new SharedState();
        state.postPeerMessage("worker-1", "worker-2", "hi");
        assertTrue(state.getInbox("worker-99").isEmpty());
        assertTrue(state.getInbox("").isEmpty());
        assertTrue(state.getInbox(null).isEmpty());
    }

    @Test
    void postPeerMessageRejectsBlankFromOrContent() {
        SharedState state = new SharedState();
        assertThrows(IllegalArgumentException.class,
                () -> state.postPeerMessage("", "worker-2", "hi"));
        assertThrows(IllegalArgumentException.class,
                () -> state.postPeerMessage("worker-1", "worker-2", "  "));
    }

    @Test
    void peerMessagesSnapshotIsImmutable() {
        SharedState state = new SharedState();
        state.postPeerMessage("worker-1", "worker-2", "hi");
        assertThrows(UnsupportedOperationException.class, () -> state.getPeerMessages().clear());
    }

    @Test
    void isEmptyFalseAfterPeerMessage() {
        SharedState state = new SharedState();
        state.postPeerMessage("worker-1", "worker-2", "hi");
        assertTrue(!state.isEmpty());
    }
}
