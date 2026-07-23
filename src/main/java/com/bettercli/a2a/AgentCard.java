package com.bettercli.a2a;

import java.util.List;

/**
 * A2A Agent Card（对标 Google A2A 协议：远程 agent 的能力发现名片）。
 *
 * <p>每个远程 agent 发布一张 Agent Card，描述自己的能力与连接信息。
 * 本地 agent 通过 Agent Card 发现远程 agent，再用 {@link A2AClient} 发 JSON-RPC 调用。
 *
 * <p>对标 A2A 定位：MCP 连 agent↔tool，A2A 连 agent↔agent。PaiCLI 的 worker 池可混编
 * 本地 SubAgent + 远程 agent（通过 AgentCard + A2AClient），实现跨服务/跨框架协作。
 *
 * @param name        agent 名（唯一标识，用于 worker 池派活）
 * @param description 能力描述（供规划者判断是否派给该远程 agent）
 * @param url         A2A endpoint URL（JSON-RPC over HTTP）
 * @param skills      能力标签列表（如 ["code-review", "security-audit"]）
 */
public record AgentCard(String name, String description, String url, List<String> skills) {
    public AgentCard {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AgentCard name 不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("AgentCard url 不能为空");
        }
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** 是否具备某能力标签。 */
    public boolean hasSkill(String skill) {
        if (skill == null || skill.isBlank()) {
            return false;
        }
        return skills.stream().anyMatch(s -> s.equalsIgnoreCase(skill.trim()));
    }
}
