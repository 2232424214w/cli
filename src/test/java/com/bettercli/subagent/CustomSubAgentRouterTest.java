package com.bettercli.subagent;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentRouterTest {

    @Test
    void parseNameExactMatch() {
        List<CustomSubAgentDefinition> agents = List.of(def("sql-analyzer", "slow sql"));
        assertEquals("sql-analyzer", CustomSubAgentRouter.parseName("sql-analyzer", agents));
        assertEquals("sql-analyzer", CustomSubAgentRouter.parseName("SQL-ANALYZER\nextra", agents));
    }

    @Test
    void parseDecisionWithConfidence() {
        List<CustomSubAgentDefinition> agents = List.of(def("sql-analyzer", "slow sql"));
        var d = CustomSubAgentRouter.parseDecision("sql-analyzer|0.91", agents);
        assertNotNull(d);
        assertEquals("sql-analyzer", d.agentName());
        assertEquals(0.91, d.confidence(), 1e-6);

        var d2 = CustomSubAgentRouter.parseDecision("sql-analyzer,0.55", agents);
        assertNotNull(d2);
        assertEquals(0.55, d2.confidence(), 1e-6);
    }

    @Test
    void parseNameNone() {
        List<CustomSubAgentDefinition> agents = List.of(def("sql-analyzer", "slow sql"));
        assertNull(CustomSubAgentRouter.parseName("NONE", agents));
        assertNull(CustomSubAgentRouter.parseName("无", agents));
        assertNull(CustomSubAgentRouter.parseName("unknown-agent", agents));
    }

    @Test
    void detectBypassPrefixes() {
        var b = CustomSubAgentRouter.detectBypass("@main 帮我写个排序");
        assertTrue(b.bypassRouter());
        assertEquals("帮我写个排序", b.message());

        var b2 = CustomSubAgentRouter.detectBypass("/main 普通任务");
        assertTrue(b2.bypassRouter());

        var b3 = CustomSubAgentRouter.detectBypass("主Agent: 继续用主模型");
        assertTrue(b3.bypassRouter());
        assertEquals("继续用主模型", b3.message());

        var b4 = CustomSubAgentRouter.detectBypass("正常消息");
        assertFalse(b4.bypassRouter());
        assertEquals("正常消息", b4.message());
    }

    @Test
    void buildPromptContainsDescriptionsAndSticky() {
        String prompt = CustomSubAgentRouter.buildPrompt(
                "帮我看慢 SQL",
                List.of(def("sql-analyzer", "分析慢 SQL")),
                "sql-analyzer");
        assertTrue(prompt.contains("sql-analyzer"));
        assertTrue(prompt.contains("分析慢 SQL"));
        assertTrue(prompt.contains("帮我看慢 SQL"));
        assertTrue(prompt.contains("NONE"));
        assertTrue(prompt.contains("confidence"));
        assertTrue(prompt.contains("上一轮曾路由到"));
    }

    @Test
    void detectDirectDesignate() {
        var d = CustomSubAgentRouter.detectDirectDesignate("/subagent:sql-analyzer 帮我看慢 SQL");
        assertTrue(d.isPresent());
        assertEquals("sql-analyzer", d.get().agentName());
        assertEquals("帮我看慢 SQL", d.get().message());

        var d2 = CustomSubAgentRouter.detectDirectDesignate("/sa:code-reviewer");
        assertTrue(d2.isPresent());
        assertEquals("code-reviewer", d2.get().agentName());
        assertTrue(d2.get().message().isBlank());

        assertTrue(CustomSubAgentRouter.detectDirectDesignate("普通消息").isEmpty());
        assertTrue(CustomSubAgentRouter.detectDirectDesignate("/subagent list").isEmpty());
    }

    @Test
    void resolveIngressPrefersDirectOverRouter() {
        var ingress = CustomSubAgentRouter.resolveIngress(
                "/subagent:sql-analyzer 分析",
                null,
                List.of(def("sql-analyzer", "slow sql"), def("other", "x")),
                null);
        assertEquals(CustomSubAgentRouter.IngressDecision.Kind.DIRECT, ingress.kind());
        assertEquals("sql-analyzer", ingress.agentName());
        assertEquals("分析", ingress.effectiveMessage());
    }

    @Test
    void minConfidenceDefault() {
        // 不依赖外部环境，至少能读到合法区间
        double min = CustomSubAgentRouter.minConfidence();
        assertTrue(min >= 0 && min <= 1);
    }

    @Test
    void resolveClientFallsBackWhenProviderUnset() {
        LlmClient fallback = new LlmClient() {
            @Override
            public ChatResponse chat(java.util.List<Message> messages, java.util.List<Tool> tools) {
                return null;
            }

            @Override
            public ChatResponse chat(java.util.List<Message> messages, java.util.List<Tool> tools,
                                     StreamListener listener) {
                return null;
            }

            @Override
            public String getModelName() {
                return "fallback-model";
            }

            @Override
            public String getProviderName() {
                return "fallback";
            }
        };
        assertSame(fallback, CustomSubAgentRouter.resolveClient(fallback, new com.bettercli.config.BetterCliConfig()));
        assertSame(fallback, CustomSubAgentRouter.resolveClient(fallback, null));
    }

    private static CustomSubAgentDefinition def(String name, String desc) {
        return new CustomSubAgentDefinition(
                name, desc, "body", null, null, null,
                List.of(), List.of(), List.of(), "",
                "", "",
                CustomSubAgentDefinition.Source.USER, null, null);
    }
}
