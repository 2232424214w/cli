package com.bettercli.memory;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCompressBeforeShortTermMemoryEvictsOldEntries() {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "压缩摘要", null, 100, 20)
        ));
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                40,
                128000,
                new LongTermMemory(tempDir.toFile())
        );
        String longMessage = "a".repeat(36);

        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);
        memoryManager.addUserMessage(longMessage);
        memoryManager.addAssistantMessage(longMessage);

        assertTrue(memoryManager.getShortTermMemory().getAll().stream()
                .anyMatch(entry -> entry.getType() == MemoryEntry.MemoryType.SUMMARY));
    }

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);

        memoryManager.storeFact("用户偏好使用中文交流");
        memoryManager.storeFact("项目路径: /tmp/demo");
        assertEquals(2, longTermMemory.size());

        memoryManager.clearLongTerm();

        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldStoreProjectScopedFactsByDefault() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");

        memoryManager.storeFact("当前项目使用 Java 17");
        memoryManager.storeFact("默认用中文回答", "global");

        MemoryEntry projectEntry = longTermMemory.search("Java", 5, memoryManager.getCurrentProject()).get(0);
        assertEquals("project", projectEntry.getMetadata().get("scope"));
        assertEquals(memoryManager.getCurrentProject(), projectEntry.getMetadata().get("project"));
        assertEquals("global", longTermMemory.search("中文", 5).get(0).getMetadata().get("scope"));
    }

    @Test
    void shouldSearchOnlyCurrentProjectAndGlobalFacts() {
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
        MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
        memoryManager.setProjectPath("/repo/current");
        longTermMemory.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", memoryManager.getCurrentProject()), 10));
        longTermMemory.store(new MemoryEntry("other", "其他项目使用 Java 8", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("scope", "project", "project", "/repo/other"), 10));

        List<MemoryEntry> results = memoryManager.searchLongTerm("Java", 10);

        assertEquals(1, results.size());
        assertEquals("current", results.get(0).getId());
    }

    @Test
    void compressionTriggerRatioAppliesToAllModelsUniformly() {
        // 验证：长 window 模型也使用自动压缩阈值，没有"长模式不压缩"的二元开关
        MemoryManager memoryManager = new MemoryManager(new GLMClient("test-key"));

        assertEquals(0.859, memoryManager.getContextProfile().compressionTriggerRatio(), 0.001);
        assertEquals(200000, memoryManager.getTokenBudget().getContextWindow());
        assertEquals(171808, memoryManager.getContextProfile().compressionTriggerTokens());
    }

    @Test
    void legacyPrefetchDisabledByDefault() {
        String old = System.getProperty("bettercli.memory.legacy_prefetch.enabled");
        try {
            System.clearProperty("bettercli.memory.legacy_prefetch.enabled");
            assertFalse(MemoryManager.isLegacyPrefetchEnabled());
            System.setProperty("bettercli.memory.legacy_prefetch.enabled", "true");
            assertTrue(MemoryManager.isLegacyPrefetchEnabled());
        } finally {
            if (old == null) {
                System.clearProperty("bettercli.memory.legacy_prefetch.enabled");
            } else {
                System.setProperty("bettercli.memory.legacy_prefetch.enabled", old);
            }
        }
    }

    @Test
    void storeFactWritesAgentMemoryOnlyByDefault() throws Exception {
        Path dbDir = tempDir.resolve("agent-mem");
        Files.createDirectories(dbDir);
        SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/repo/demo", dbDir.toFile(), 100, 0.85);
        String oldDual = System.getProperty("bettercli.memory.json_dual_write.enabled");
        try {
            System.clearProperty("bettercli.memory.json_dual_write.enabled");
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("json").toFile());
            MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
            memoryManager.setProjectPath("/repo/demo");
            memoryManager.setAgentMemoryStore(store);

            memoryManager.storeFact("当前项目使用 Java 17 和 Maven", "project");

            assertEquals(0, longTermMemory.size(), "默认不双写 JSON");
            assertEquals(1, store.size());
            assertTrue(store.list(MemoryListQuery.builder().limit(10).build()).stream()
                    .anyMatch(e -> e.getContent().contains("Java 17")));
        } finally {
            store.close();
            if (oldDual == null) {
                System.clearProperty("bettercli.memory.json_dual_write.enabled");
            } else {
                System.setProperty("bettercli.memory.json_dual_write.enabled", oldDual);
            }
        }
    }

    @Test
    void storeFactDualWritesJsonWhenEnabled() throws Exception {
        Path dbDir = tempDir.resolve("agent-mem-dual");
        Files.createDirectories(dbDir);
        SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/repo/demo", dbDir.toFile(), 100, 0.85);
        String oldDual = System.getProperty("bettercli.memory.json_dual_write.enabled");
        try {
            System.setProperty("bettercli.memory.json_dual_write.enabled", "true");
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("json-dual").toFile());
            MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
            memoryManager.setProjectPath("/repo/demo");
            memoryManager.setAgentMemoryStore(store);

            memoryManager.storeFact("双写测试事实 Maven Java", "project");

            assertEquals(1, longTermMemory.size());
            assertEquals(1, store.size());
        } finally {
            store.close();
            if (oldDual == null) {
                System.clearProperty("bettercli.memory.json_dual_write.enabled");
            } else {
                System.setProperty("bettercli.memory.json_dual_write.enabled", oldDual);
            }
        }
    }

    @Test
    void buildContextForQueryPrefersAgentMemoryBm25() throws Exception {
        Path dbDir = tempDir.resolve("agent-mem-prefetch");
        Files.createDirectories(dbDir);
        SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/repo/demo", dbDir.toFile(), 100, 0.85);
        try {
            store.store(AgentMemoryEntry.builder()
                    .id("mem-pref")
                    .content("项目选用 PostgreSQL 作为主库")
                    .keywords(List.of("PostgreSQL", "数据库", "主库"))
                    .type(AgentMemoryEntry.MemoryType.FACT)
                    .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                    .project("/repo/demo")
                    .confidence(1.0)
                    .source(AgentMemoryEntry.MemorySource.EXPLICIT_HINT)
                    .status(AgentMemoryEntry.MemoryStatus.ACTIVE)
                    .build());
            LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("json-pref").toFile());
            MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 32768, 128000, longTermMemory);
            memoryManager.setProjectPath("/repo/demo");
            memoryManager.setAgentMemoryStore(store);

            String ctx = memoryManager.buildContextForQuery("PostgreSQL 主库", 500);
            assertTrue(ctx.contains("agent_memory BM25"), ctx);
            assertTrue(ctx.contains("PostgreSQL"), ctx);
        } finally {
            store.close();
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
