package com.bettercli.a2a;

import com.bettercli.agent.AgentMessage;
import com.bettercli.agent.AgentRole;
import com.bettercli.agent.Worker;

import java.io.PrintStream;

/**
 * 远程 agent 适配器：把 A2A 远程 agent 包装成本地可调用的 worker。
 *
 * <p>对标 A2A 定位：MCP 连 agent↔tool，A2A 连 agent↔agent。PaiCLI worker 池可混编
 * 本地 {@code SubAgent} + 远程 agent（通过本类）。
 *
 * <p>本类提供与 {@code SubAgent.executeWithContext} 同签名的方法，便于后续提取
 * {@code Worker} 接口让 SubAgent 和 RemoteAgent 共同实现、混编进 worker 池。
 * 本轮作为独立可测模块交付，orchestrator 集成留作后续（需提取 Worker 接口）。
 *
 * <p>执行语义：把 context 拼到 message 里，通过 {@link A2AClient#executeAndWait} 发给远程，
 * 远程返回的 content 包成 {@link AgentMessage#result}。远程失败则返回 {@link AgentMessage#error}。
 */
public class RemoteAgent implements Worker {

    private final AgentCard card;
    private final A2AClient client;
    private final AgentRole role;

    public RemoteAgent(AgentCard card, A2AClient client) {
        this(card, client, AgentRole.WORKER);
    }

    public RemoteAgent(AgentCard card, A2AClient client, AgentRole role) {
        if (card == null) throw new IllegalArgumentException("AgentCard 不能为空");
        if (client == null) throw new IllegalArgumentException("A2AClient 不能为空");
        this.card = card;
        this.client = client;
        this.role = role == null ? AgentRole.WORKER : role;
    }

    public String getName() {
        return card.name();
    }

    public AgentRole getRole() {
        return role;
    }

    public AgentCard getCard() {
        return card;
    }

    /** 与 SubAgent.execute 同签名：发任务给远程 agent，返回结果消息。 */
    public AgentMessage execute(AgentMessage task) {
        return executeWithContext(task, null, null);
    }

    public AgentMessage execute(AgentMessage task, PrintStream out) {
        return executeWithContext(task, null, out);
    }

    /** 与 SubAgent.executeWithContext 同签名：把 context 拼到 message 发给远程。 */
    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, null);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String payload = task.content() == null ? "" : task.content();
        if (context != null && !context.isBlank()) {
            payload = context + "\n\n当前任务：" + payload;
        }
        if (out != null) {
            out.println("🌐 调用远程 agent [" + card.name() + "] @ " + card.url());
        }
        try {
            A2AClient.TaskResult result = client.executeAndWait(card, payload);
            if (result.state() == A2AClient.TaskState.COMPLETED) {
                return AgentMessage.result(card.name(), role, result.content() == null ? "" : result.content());
            }
            String err = result.error() != null ? result.error() : "远程 agent 未完成, state=" + result.state();
            return AgentMessage.error(card.name(), role, err);
        } catch (A2AException e) {
            return AgentMessage.error(card.name(), role, "A2A 调用失败: " + e.getMessage());
        }
    }

    /** 与 SubAgent.clearHistory 同签名：远程 agent 无本地历史，空实现。 */
    public void clearHistory() {
        // no-op：远程 agent 的对话历史在远端，本地无状态可清
    }
}
