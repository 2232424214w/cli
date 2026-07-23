package com.bettercli.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A2A 客户端（对标 Google A2A 协议：JSON-RPC 2.0 over HTTP）。
 *
 * <p>核心方法：
 * <ul>
 *   <li>{@link #sendTask}：向远程 agent 发任务，返回 task id（状态 submitted/working）。</li>
 *   <li>{@link #getTask}：查询任务状态与产物（submitted/working/completed/failed）。</li>
 *   <li>{@link #executeAndWait}：发任务后轮询到 completed/failed，返回最终产物（同步便捷封装）。</li>
 * </ul>
 *
 * <p>传输层通过 {@link HttpTransport} 注入，生产用 {@link HttpTransport#http()}，
 * 测试用 mock 直接返回预设 JSON，不起真实 HTTP server。
 *
 * <p>对标 A2A 定位：MCP 连 agent↔tool，A2A 连 agent↔agent。PaiCLI worker 池可混编
 * 本地 SubAgent + 远程 agent（通过 {@link AgentCard} + A2AClient）。
 */
public class A2AClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String METHOD_SEND = "tasks/send";
    private static final String METHOD_GET = "tasks/get";

    private final HttpTransport transport;
    private final AtomicLong idSeq = new AtomicLong(1);
    private final long pollIntervalMillis;
    private final int maxPolls;

    public A2AClient() {
        this(HttpTransport.http(), 1000L, 120);
    }

    public A2AClient(HttpTransport transport) {
        this(transport, 1000L, 120);
    }

    public A2AClient(HttpTransport transport, long pollIntervalMillis, int maxPolls) {
        this.transport = transport;
        this.pollIntervalMillis = pollIntervalMillis;
        this.maxPolls = maxPolls;
    }

    /** 远程任务状态。 */
    public enum TaskState { SUBMITTED, WORKING, COMPLETED, FAILED, UNKNOWN }

    /** 远程任务查询结果。 */
    public record TaskResult(TaskState state, String content, String error) {
        public static TaskResult completed(String content) { return new TaskResult(TaskState.COMPLETED, content, null); }
        public static TaskResult failed(String error) { return new TaskResult(TaskState.FAILED, null, error); }
    }

    /** 发送任务，返回 task id。 */
    public String sendTask(AgentCard card, String message) throws A2AException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("message", message);
        String body = buildJsonRpc(METHOD_SEND, params);
        String resp = transport.post(card.url(), body);
        return extractTaskId(resp);
    }

    /** 查询任务状态与产物。 */
    public TaskResult getTask(AgentCard card, String taskId) throws A2AException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("taskId", taskId);
        String body = buildJsonRpc(METHOD_GET, params);
        String resp = transport.post(card.url(), body);
        return parseTaskResult(resp);
    }

    /**
     * 发任务并轮询到终态（completed/failed），返回最终产物。
     * 轮询间隔与次数由构造参数控制，默认 1s × 120 次（2 分钟上限），防无限等待。
     */
    public TaskResult executeAndWait(AgentCard card, String message) throws A2AException {
        String taskId = sendTask(card, message);
        for (int i = 0; i < maxPolls; i++) {
            TaskResult result = getTask(card, taskId);
            if (result.state() == TaskState.COMPLETED) {
                return result;
            }
            if (result.state() == TaskState.FAILED) {
                return result;
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new A2AException("A2A 轮询被中断, taskId=" + taskId, e);
            }
        }
        return new TaskResult(TaskState.UNKNOWN, null, "轮询超时, taskId=" + taskId);
    }

    private String buildJsonRpc(String method, ObjectNode params) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", idSeq.getAndIncrement());
        root.put("method", method);
        root.set("params", params);
        return root.toString();
    }

    private String extractTaskId(String resp) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            JsonNode result = root.path("result");
            JsonNode id = result.path("taskId");
            if (id.isMissingNode()) {
                // 兼容直接返回 id 字段
                id = result.path("id");
            }
            if (id.isMissingNode() || id.asText().isEmpty()) {
                throw new A2AException("A2A 响应缺少 taskId: " + resp);
            }
            return id.asText();
        } catch (A2AException e) {
            throw e;
        } catch (Exception e) {
            throw new A2AException("A2A 解析 taskId 失败: " + e.getMessage(), e);
        }
    }

    private TaskResult parseTaskResult(String resp) {
        try {
            JsonNode root = MAPPER.readTree(resp);
            JsonNode result = root.path("result");
            String stateStr = result.path("state").asText("UNKNOWN").toUpperCase();
            TaskState state;
            try {
                state = TaskState.valueOf(stateStr);
            } catch (IllegalArgumentException e) {
                state = TaskState.UNKNOWN;
            }
            String content = result.path("content").asText(null);
            String error = result.path("error").asText(null);
            return new TaskResult(state, content, error);
        } catch (Exception e) {
            throw new A2AException("A2A 解析任务结果失败: " + e.getMessage(), e);
        }
    }
}
