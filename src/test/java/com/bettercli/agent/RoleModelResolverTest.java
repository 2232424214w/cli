package com.bettercli.agent;

import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RoleModelResolverTest {

    @Test
    void shouldFallbackToDefaultClientWhenNoConfig() {
        LlmClient defaultClient = new GLMClient("default-key", "default-model");
        RoleModelResolver resolver = new RoleModelResolver(defaultClient, new BetterCliConfig());

        // 没配任何角色 provider，所有角色都应回退默认 client
        assertSame(defaultClient, resolver.apply(AgentRole.PLANNER));
        assertSame(defaultClient, resolver.apply(AgentRole.WORKER));
        assertSame(defaultClient, resolver.apply(AgentRole.REVIEWER));
    }

    @Test
    void shouldFallbackToDefaultWhenRoleClientCannotBeBuilt() {
        LlmClient defaultClient = new GLMClient("default-key", "default-model");
        BetterCliConfig config = new BetterCliConfig();
        // 配了 planner provider=deepseek，但没配 deepseek 的 api key → LlmClientFactory.create 返回 null → 回退默认
        String prev = System.getProperty("bettercli.team.planner.provider");
        try {
            System.setProperty("bettercli.team.planner.provider", "deepseek");
            RoleModelResolver resolver = new RoleModelResolver(defaultClient, config);
            assertSame(defaultClient, resolver.apply(AgentRole.PLANNER),
                    "configured-but-unbuildable provider should fall back to default, not break /team");
        } finally {
            if (prev == null) {
                System.clearProperty("bettercli.team.planner.provider");
            } else {
                System.setProperty("bettercli.team.planner.provider", prev);
            }
        }
    }

    @Test
    void shouldResolveRoleSpecificClientFromConfig() {
        LlmClient defaultClient = new GLMClient("default-key", "default-model");

        // 配 glm provider 带 key 和 model
        BetterCliConfig config = new BetterCliConfig();
        BetterCliConfig.ProviderConfig glm = new BetterCliConfig.ProviderConfig("glm-key", null, "glm-flash");
        config.getProviders().put("glm", glm);

        String prev = System.getProperty("bettercli.team.planner.provider");
        try {
            System.setProperty("bettercli.team.planner.provider", "glm");
            RoleModelResolver resolver = new RoleModelResolver(defaultClient, config);

            LlmClient plannerClient = resolver.apply(AgentRole.PLANNER);
            assertNotEquals(defaultClient, plannerClient,
                    "planner should get a dedicated client when configured");
            assertEquals("glm-flash", plannerClient.getModelName(),
                    "planner client should use the configured model");
            assertEquals("glm", plannerClient.getProviderName());

            // WORKER 未配，仍回退默认
            assertSame(defaultClient, resolver.apply(AgentRole.WORKER));
        } finally {
            if (prev == null) {
                System.clearProperty("bettercli.team.planner.provider");
            } else {
                System.setProperty("bettercli.team.planner.provider", prev);
            }
        }
    }

    @Test
    void shouldReturnDefaultClientForNullRoleOrConfig() {
        LlmClient defaultClient = new GLMClient("default-key", "default-model");
        assertSame(defaultClient, new RoleModelResolver(defaultClient, null).apply(AgentRole.PLANNER));
        assertSame(defaultClient, new RoleModelResolver(defaultClient, new BetterCliConfig()).apply(null));
    }
}
