package com.bettercli.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * 向运行中的子 Agent 注入纠偏指令（对齐 1024 steer_agent）。
 * 消息仅在下一轮 LLM 请求中临时附带，不写入持久会话历史。
 */
public final class AgentSteerService {

    private final ConcurrentMap<String, ConcurrentLinkedQueue<String>> queues = new ConcurrentHashMap<>();

    public void enqueue(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || message == null || message.isBlank()) {
            return;
        }
        queues.computeIfAbsent(sessionId.trim(), k -> new ConcurrentLinkedQueue<>())
                .add(message.trim());
    }

    /** 取出并清空该 session 的待注入指令。 */
    public List<String> drain(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        ConcurrentLinkedQueue<String> q = queues.remove(sessionId.trim());
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(q);
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        queues.remove(sessionId.trim());
    }

    public int pendingCount(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        ConcurrentLinkedQueue<String> q = queues.get(sessionId.trim());
        return q == null ? 0 : q.size();
    }

    public void clearAll() {
        queues.clear();
    }
}
