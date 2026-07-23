package com.bettercli.a2a;

import com.bettercli.agent.AgentMessage;
import com.bettercli.agent.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 RemoteAgent：把 A2A 远程 agent 包装成本地可调用的 worker，
 * 提供 SubAgent 兼容的执行接口（为后续混编 worker 池做准备）。
 */
class RemoteAgentTest {

    @Test
    void executeReturnsResultMessageWhenRemoteCompletes() {
        AgentCard card = new AgentCard("remote-1", "desc", "http://x", List.of("code-review"));
        A2AClient client = new A2AClient((url, body) -> """
                {"jsonrpc":"2.0","result":{"taskId":"t1"}}
                """) {
            @Override
            public A2AClient.TaskResult executeAndWait(AgentCard c, String message) {
                return A2AClient.TaskResult.completed("远程审查通过");
            }
        };
        RemoteAgent agent = new RemoteAgent(card, client);

        AgentMessage result = agent.execute(AgentMessage.task("orchestrator", "审查这段代码"));

        assertEquals(AgentMessage.Type.RESULT, result.type());
        assertEquals("remote-1", result.fromAgent());
        assertEquals("远程审查通过", result.content());
    }

    @Test
    void executeReturnsErrorWhenRemoteFails() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        A2AClient client = new A2AClient((url, body) -> "{}") {
            @Override
            public A2AClient.TaskResult executeAndWait(AgentCard c, String message) {
                return A2AClient.TaskResult.failed("远端超时");
            }
        };
        RemoteAgent agent = new RemoteAgent(card, client);

        AgentMessage result = agent.execute(AgentMessage.task("o", "x"));

        assertEquals(AgentMessage.Type.ERROR, result.type());
        assertTrue(result.content().contains("远端超时"));
    }

    @Test
    void executeReturnsErrorOnA2AException() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        A2AClient client = new A2AClient((url, body) -> { throw new A2AException("连接拒绝"); }) {
            @Override
            public A2AClient.TaskResult executeAndWait(AgentCard c, String message) {
                throw new A2AException("连接拒绝");
            }
        };
        RemoteAgent agent = new RemoteAgent(card, client);

        AgentMessage result = agent.execute(AgentMessage.task("o", "x"));

        assertEquals(AgentMessage.Type.ERROR, result.type());
        assertTrue(result.content().contains("连接拒绝"));
    }

    @Test
    void executeWithContextPrependsContextToPayload() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        A2AClient client = new A2AClient((url, body) -> "{}") {
            @Override
            public A2AClient.TaskResult executeAndWait(AgentCard c, String message) {
                captured.set(message);
                return A2AClient.TaskResult.completed("ok");
            }
        };
        RemoteAgent agent = new RemoteAgent(card, client);

        agent.executeWithContext(AgentMessage.task("o", "做X"), "前置上下文");

        String payload = captured.get();
        assertTrue(payload.contains("前置上下文"));
        assertTrue(payload.contains("做X"));
        assertTrue(payload.contains("当前任务"));
    }

    @Test
    void roleDefaultsToWorker() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        RemoteAgent agent = new RemoteAgent(card, new A2AClient((u, b) -> "{}"));
        assertEquals(AgentRole.WORKER, agent.getRole());
        assertEquals("r", agent.getName());
    }

    @Test
    void clearHistoryIsNoOp() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        RemoteAgent agent = new RemoteAgent(card, new A2AClient((u, b) -> "{}"));
        // 不抛异常即可
        agent.clearHistory();
        assertEquals("r", agent.getName());
    }
}
