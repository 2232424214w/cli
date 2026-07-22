package com.bettercli.agent;

import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmClientFactory;

import java.util.Locale;
import java.util.function.Function;

/**
 * 按角色解析 {@link LlmClient}，实现 Multi-Agent 的角色级模型分配。
 *
 * 设计目标：让 Planner / Reviewer / Worker 用不同模型——
 * - Planner 只拆任务，可用便宜快模型（如 glm-flash）省成本
 * - Reviewer 要判断质量，可用强推理模型（如 deepseek-thinking / kimi-thinking）保质量
 * - Worker 沿用用户当前主模型
 *
 * 配置来源（优先级从高到低）：
 * 1. 系统属性 {@code -Dbettercli.team.<role>.provider} / {@code -Dbettercli.team.<role>.model}
 * 2. 环境变量 {@code BETTERCLI_TEAM_<ROLE>_PROVIDER} / {@code BETTERCLI_TEAM_<ROLE>_MODEL}
 * 3. 回退到 {@code defaultClient}（向后兼容：不配则所有角色共用主模型，行为不变）
 *
 * 这是对标 AutoGen 的"不同 agent 用不同 model"能力，落到 BetterCLI 的配置体系上。
 */
public class RoleModelResolver implements Function<AgentRole, LlmClient> {

    private final LlmClient defaultClient;
    private final BetterCliConfig config;

    public RoleModelResolver(LlmClient defaultClient, BetterCliConfig config) {
        this.defaultClient = defaultClient;
        this.config = config;
    }

    @Override
    public LlmClient apply(AgentRole role) {
        if (role == null || config == null) {
            return defaultClient;
        }
        String provider = resolveProvider(role);
        if (provider == null || provider.isBlank()) {
            return defaultClient;
        }
        LlmClient client = LlmClientFactory.create(provider, config);
        if (client == null) {
            // 配了但建不出来（key 缺失等）：回退默认，不因模型分配让整个 /team 挂掉
            return defaultClient;
        }
        return client;
    }

    private String resolveProvider(AgentRole role) {
        String roleKey = role.name().toLowerCase(Locale.ROOT);
        String sysProp = System.getProperty("bettercli.team." + roleKey + ".provider");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        String env = System.getenv("BETTERCLI_TEAM_" + role.name().toUpperCase(Locale.ROOT) + "_PROVIDER");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }
}
