package com.bettercli.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 A2AClient（JSON-RPC over 可注入 HttpTransport，对标 Google A2A 协议）。
 * 用 mock transport 直接返回预设 JSON，不起真实 HTTP server。
 */
class A2AClientTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void sendTaskParsesTaskIdFromResponse() {
        AgentCard card = new AgentCard("remote-1", "desc", "http://x/a2a", List.of());
        HttpTransport mock = (url, body) -> rpcResult(obj -> obj.put("taskId", "task-42"));
        A2AClient client = new A2AClient(mock);

        String taskId = client.sendTask(card, "hello");

        assertEquals("task-42", taskId);
    }

    @Test
    void sendTaskFallsBackToIdField() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        HttpTransport mock = (url, body) -> rpcResult(obj -> obj.put("id", "task-7"));
        A2AClient client = new A2AClient(mock);

        assertEquals("task-7", client.sendTask(card, "hi"));
    }

    @Test
    void sendTaskThrowsWhenTaskIdMissing() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        HttpTransport mock = (url, body) -> rpcResult(obj -> {});
        A2AClient client = new A2AClient(mock);

        A2AException ex = assertThrows(A2AException.class, () -> client.sendTask(card, "hi"));
        assertTrue(ex.getMessage().contains("taskId"));
    }

    @Test
    void getTaskParsesCompletedStateAndContent() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        HttpTransport mock = (url, body) -> rpcResult(obj -> {
            obj.put("state", "completed");
            obj.put("content", "远程结果");
        });
        A2AClient client = new A2AClient(mock);

        A2AClient.TaskResult r = client.getTask(card, "task-1");

        assertEquals(A2AClient.TaskState.COMPLETED, r.state());
        assertEquals("远程结果", r.content());
    }

    @Test
    void getTaskParsesFailedStateAndError() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        HttpTransport mock = (url, body) -> rpcResult(obj -> {
            obj.put("state", "failed");
            obj.put("error", "远端崩溃");
        });
        A2AClient client = new A2AClient(mock);

        A2AClient.TaskResult r = client.getTask(card, "task-1");

        assertEquals(A2AClient.TaskState.FAILED, r.state());
        assertEquals("远端崩溃", r.error());
    }

    @Test
    void getTaskHandlesUnknownState() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        HttpTransport mock = (url, body) -> rpcResult(obj -> obj.put("state", "weird-state"));
        A2AClient client = new A2AClient(mock);

        A2AClient.TaskResult r = client.getTask(card, "task-1");
        assertEquals(A2AClient.TaskState.UNKNOWN, r.state());
    }

    @Test
    void executeAndWaitPollsUntilCompleted() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        // 第 1 次 sendTask 返回 taskId；之后 getTask 先 working 再 completed
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport mock = (url, body) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                return rpcResult(obj -> obj.put("taskId", "task-9"));
            }
            if (n == 2) {
                return rpcResult(obj -> obj.put("state", "working"));
            }
            return rpcResult(obj -> {
                obj.put("state", "completed");
                obj.put("content", "最终结果");
            });
        };
        A2AClient client = new A2AClient(mock, 1L, 50); // 1ms 轮询，快速

        A2AClient.TaskResult r = client.executeAndWait(card, "do work");

        assertEquals(A2AClient.TaskState.COMPLETED, r.state());
        assertEquals("最终结果", r.content());
    }

    @Test
    void executeAndWaitReturnsFailedImmediately() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport mock = (url, body) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) return rpcResult(obj -> obj.put("taskId", "task-9"));
            return rpcResult(obj -> {
                obj.put("state", "failed");
                obj.put("error", "远端拒绝");
            });
        };
        A2AClient client = new A2AClient(mock, 1L, 50);

        A2AClient.TaskResult r = client.executeAndWait(card, "do work");

        assertEquals(A2AClient.TaskState.FAILED, r.state());
        assertEquals("远端拒绝", r.error());
    }

    @Test
    void executeAndWaitTimesOutToUnknown() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport mock = (url, body) -> {
            int n = callCount.incrementAndGet();
            if (n == 1) return rpcResult(obj -> obj.put("taskId", "task-9"));
            return rpcResult(obj -> obj.put("state", "working")); // 永远 working
        };
        A2AClient client = new A2AClient(mock, 1L, 3); // 只轮询 3 次

        A2AClient.TaskResult r = client.executeAndWait(card, "do work");

        assertEquals(A2AClient.TaskState.UNKNOWN, r.state());
        assertTrue(r.error().contains("超时"));
    }

    @Test
    void transportExceptionPropagates() {
        AgentCard card = new AgentCard("r", "d", "http://x", List.of());
        HttpTransport mock = (url, body) -> { throw new A2AException("网络断了"); };
        A2AClient client = new A2AClient(mock);

        A2AException ex = assertThrows(A2AException.class, () -> client.sendTask(card, "hi"));
        assertTrue(ex.getMessage().contains("网络断了"));
    }

    /** 构造 {"jsonrpc":"2.0","result":<filled obj>} 响应。 */
    private static String rpcResult(java.util.function.Consumer<ObjectNode> filler) {
        ObjectNode root = M.createObjectNode();
        root.put("jsonrpc", "2.0");
        ObjectNode result = M.createObjectNode();
        filler.accept(result);
        root.set("result", result);
        return root.toString();
    }
}
