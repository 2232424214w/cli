package com.bettercli.agent;

import com.bettercli.a2a.A2AClient;
import com.bettercli.a2a.AgentCard;
import com.bettercli.a2a.RemoteAgent;
import com.bettercli.llm.GLMClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MixedWorkerPool：本地 SubAgent + 远程 RemoteAgent 混编调度。
 * 对标 2026 A2A + Claude Code agent teams：worker 池可混编本地与远程 agent。
 */
class MixedWorkerPoolTest {

    @Test
    void mixesLocalAndRemoteWorkers() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "desc", "http://x", List.of("security")),
                new A2AClient((u, b) -> "{}"));

        MixedWorkerPool pool = new MixedWorkerPool(List.of(local, remote));

        assertEquals(2, pool.size());
        assertTrue(pool.hasLocal(), "应含本地 worker");
        assertTrue(pool.hasRemote(), "应含远程 worker");
    }

    @Test
    void findReturnsLocalByName() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "d", "http://x", List.of()),
                new A2AClient((u, b) -> "{}"));
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local, remote));

        Worker found = pool.find("worker-1");
        assertNotNull(found);
        assertEquals("worker-1", found.getName());
        assertTrue(found instanceof SubAgent);
    }

    @Test
    void findReturnsRemoteByName() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "d", "http://x", List.of()),
                new A2AClient((u, b) -> "{}"));
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local, remote));

        Worker found = pool.find("remote-1");
        assertNotNull(found);
        assertEquals("remote-1", found.getName());
        assertTrue(found instanceof RemoteAgent);
    }

    @Test
    void findReturnsNullForUnknown() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local));

        assertEquals(null, pool.find("nope"));
        assertEquals(null, pool.find(null));
        assertEquals(null, pool.find("  "));
    }

    @Test
    void roundRobinCyclesThroughWorkers() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "d", "http://x", List.of()),
                new A2AClient((u, b) -> "{}"));
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local, remote));

        assertEquals("worker-1", pool.roundRobin(0).getName());
        assertEquals("remote-1", pool.roundRobin(1).getName());
        assertEquals("worker-1", pool.roundRobin(2).getName());
        // 负游标也能正确回绕
        assertEquals("remote-1", pool.roundRobin(-1).getName());
    }

    @Test
    void pickPrefersNamedAssignment() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "d", "http://x", List.of()),
                new A2AClient((u, b) -> "{}"));
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local, remote));

        MixedWorkerPool.Routing r = pool.pick("remote-1", 0);
        assertEquals("remote-1", r.worker().getName());
        assertTrue(r.reason().contains("规划者指派"));
    }

    @Test
    void pickFallsBackToRoundRobinWhenNamedMissing() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local));

        MixedWorkerPool.Routing r = pool.pick("ghost", 0);
        assertEquals("worker-1", r.worker().getName());
        assertTrue(r.reason().contains("不存在"));
    }

    @Test
    void pickRoundRobinWhenAssigneeBlank() {
        SubAgent local = new SubAgent("worker-1", AgentRole.WORKER,
                new GLMClient("test-key"), new ToolRegistry());
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "d", "http://x", List.of()),
                new A2AClient((u, b) -> "{}"));
        MixedWorkerPool pool = new MixedWorkerPool(List.of(local, remote));

        MixedWorkerPool.Routing r = pool.pick(null, 1);
        assertEquals("remote-1", r.worker().getName());
        assertTrue(r.reason().contains("未指派"));
    }

    @Test
    void rejectsEmptyPool() {
        assertThrows(IllegalArgumentException.class, () -> new MixedWorkerPool(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MixedWorkerPool(null));
    }

    @Test
    void allRemotePoolHasNoLocal() {
        RemoteAgent remote = new RemoteAgent(
                new AgentCard("remote-1", "d", "http://x", List.of()),
                new A2AClient((u, b) -> "{}"));
        MixedWorkerPool pool = new MixedWorkerPool(List.of(remote));

        assertTrue(pool.hasRemote());
        assertFalse(pool.hasLocal());
    }
}
