package com.bettercli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteAgentMemoryStoreTest {

    private SqliteAgentMemoryStore createStore(File memoryDir, String projectPath) throws Exception {
        return new SqliteAgentMemoryStore(projectPath, memoryDir, 100, 0.85);
    }

    @Test
    void storeAndRetrieveRoundTrip(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            AgentMemoryEntry entry = AgentMemoryEntry.builder()
                    .id("fact-1")
                    .content("项目使用 SQLite 作为本地存储")
                    .keywords(List.of("SQLite", "存储"))
                    .type(AgentMemoryEntry.MemoryType.FACT)
                    .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                    .project("/project/test")
                    .confidence(0.9)
                    .source(AgentMemoryEntry.MemorySource.AGENT_TOOL)
                    .build();

            store.store(entry);

            Optional<AgentMemoryEntry> retrieved = store.retrieve("fact-1");
            assertTrue(retrieved.isPresent());
            assertEquals("项目使用 SQLite 作为本地存储", retrieved.get().getContent());
            assertEquals(List.of("SQLite", "存储"), retrieved.get().getKeywords());
            assertEquals(AgentMemoryEntry.MemoryType.FACT, retrieved.get().getType());
            assertEquals(0.9, retrieved.get().getConfidence());
        }
    }

    @Test
    void storeOverwritesExistingId(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            AgentMemoryEntry original = AgentMemoryEntry.builder()
                    .id("fact-1").content("原始内容").confidence(0.5).build();
            store.store(original);

            AgentMemoryEntry updated = AgentMemoryEntry.builder()
                    .id("fact-1").content("更新后的内容").confidence(0.9).build();
            store.store(updated);

            Optional<AgentMemoryEntry> retrieved = store.retrieve("fact-1");
            assertTrue(retrieved.isPresent());
            assertEquals("更新后的内容", retrieved.get().getContent());
            assertEquals(0.9, retrieved.get().getConfidence());
            assertEquals(1, store.size());
        }
    }

    @Test
    void deleteRemovesEntry(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("fact-1").content("内容").build());
            assertEquals(1, store.size());

            assertTrue(store.delete("fact-1"));
            assertEquals(0, store.size());
            assertTrue(store.retrieve("fact-1").isEmpty());
        }
    }

    @Test
    void deleteReturnsFalseForMissingId(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            assertFalse(store.delete("nonexistent"));
        }
    }

    @Test
    void clearRemovesAllEntries(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("f1").content("内容1").build());
            store.store(AgentMemoryEntry.builder().id("f2").content("内容2").build());
            store.store(AgentMemoryEntry.builder().id("f3").content("内容3").build());
            assertEquals(3, store.size());

            store.clear();
            assertEquals(0, store.size());
        }
    }

    @Test
    void storeRejectsWhenOverCapacity(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            for (int i = 0; i < 100; i++) {
                store.store(AgentMemoryEntry.builder()
                        .id("fact-" + i).content("内容" + i).build());
            }
            assertEquals(100, store.size());

            assertThrows(IllegalStateException.class, () ->
                    store.store(AgentMemoryEntry.builder()
                            .id("fact-100").content("超限").build()));
        }
    }

    @Test
    void updateModifiesEntryFields(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("fact-1")
                    .content("原始内容")
                    .keywords(List.of("kw1"))
                    .confidence(0.5)
                    .type(AgentMemoryEntry.MemoryType.FACT)
                    .build());

            MemoryEntryPatch patch = MemoryEntryPatch.builder()
                    .content("更新后的内容")
                    .keywords(List.of("kw2", "kw3"))
                    .confidence(0.9)
                    .type(AgentMemoryEntry.MemoryType.PATTERN)
                    .build();

            assertTrue(store.update("fact-1", patch));

            Optional<AgentMemoryEntry> retrieved = store.retrieve("fact-1");
            assertTrue(retrieved.isPresent());
            assertEquals("更新后的内容", retrieved.get().getContent());
            assertEquals(List.of("kw2", "kw3"), retrieved.get().getKeywords());
            assertEquals(0.9, retrieved.get().getConfidence());
            assertEquals(AgentMemoryEntry.MemoryType.PATTERN, retrieved.get().getType());
        }
    }

    @Test
    void updateReturnsFalseForMissingId(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            MemoryEntryPatch patch = MemoryEntryPatch.builder().content("新内容").build();
            assertFalse(store.update("nonexistent", patch));
        }
    }

    @Test
    void updateReturnsFalseForEmptyPatch(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("fact-1").content("内容").build());
            assertFalse(store.update("fact-1", MemoryEntryPatch.builder().build()));
        }
    }

    @Test
    void searchReturnsMatchingEntries(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("sqlite-fact")
                    .content("项目使用 SQLite 作为本地存储数据库")
                    .keywords(List.of("SQLite", "数据库"))
                    .confidence(0.9)
                    .build());
            store.store(AgentMemoryEntry.builder()
                    .id("postgres-fact")
                    .content("云端部署使用 PostgreSQL")
                    .keywords(List.of("PostgreSQL", "云端"))
                    .confidence(0.8)
                    .build());

            MemorySearchQuery query = MemorySearchQuery.builder()
                    .query("SQLite 数据库")
                    .limit(5)
                    .build();

            List<MemorySearchResult> results = store.search(query);
            assertFalse(results.isEmpty());
            assertTrue(results.stream().anyMatch(r -> r.entry().getId().equals("sqlite-fact")));
        }
    }

    @Test
    void searchReturnsEmptyForBlankQuery(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("f1").content("内容").build());

            assertTrue(store.search(MemorySearchQuery.builder().query("").build()).isEmpty());
            assertTrue(store.search(MemorySearchQuery.builder().query("   ").build()).isEmpty());
            assertTrue(store.search(MemorySearchQuery.builder().query(null).build()).isEmpty());
        }
    }

    @Test
    void searchFiltersByType(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("fact-1").content("SQLite 存储").type(AgentMemoryEntry.MemoryType.FACT).build());
            store.store(AgentMemoryEntry.builder()
                    .id("pattern-1").content("SQLite 使用模式").type(AgentMemoryEntry.MemoryType.PATTERN).build());

            MemorySearchQuery query = MemorySearchQuery.builder()
                    .query("SQLite")
                    .type(AgentMemoryEntry.MemoryType.FACT)
                    .build();

            List<MemorySearchResult> results = store.search(query);
            assertTrue(results.stream().allMatch(r -> r.entry().getType() == AgentMemoryEntry.MemoryType.FACT));
        }
    }

    @Test
    void searchFiltersByScopeAndProject(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("proj-fact")
                    .content("项目级 SQLite 事实")
                    .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                    .project("/project/test")
                    .build());
            store.store(AgentMemoryEntry.builder()
                    .id("other-fact")
                    .content("其他项目 SQLite 事实")
                    .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                    .project("/project/other")
                    .build());
            store.store(AgentMemoryEntry.builder()
                    .id("global-fact")
                    .content("全局 SQLite 事实")
                    .scope(AgentMemoryEntry.MemoryScope.GLOBAL)
                    .build());

            MemorySearchQuery query = MemorySearchQuery.builder()
                    .query("SQLite")
                    .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                    .project("/project/test")
                    .build();

            List<MemorySearchResult> results = store.search(query);
            assertTrue(results.stream().anyMatch(r -> r.entry().getId().equals("proj-fact")));
            assertTrue(results.stream().anyMatch(r -> r.entry().getId().equals("global-fact")));
            assertFalse(results.stream().anyMatch(r -> r.entry().getId().equals("other-fact")));
        }
    }

    @Test
    void searchFiltersByMinConfidence(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("high-conf").content("SQLite 高置信度").confidence(0.9).build());
            store.store(AgentMemoryEntry.builder()
                    .id("low-conf").content("SQLite 低置信度").confidence(0.3).build());

            MemorySearchQuery query = MemorySearchQuery.builder()
                    .query("SQLite")
                    .minConfidence(0.7)
                    .build();

            List<MemorySearchResult> results = store.search(query);
            assertTrue(results.stream().allMatch(r -> r.entry().getConfidence() >= 0.7));
        }
    }

    @Test
    void searchUpdatesAccessCount(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("fact-1").content("SQLite 存储").build());

            store.search(MemorySearchQuery.builder().query("SQLite").build());
            store.search(MemorySearchQuery.builder().query("SQLite").build());

            Optional<AgentMemoryEntry> retrieved = store.retrieve("fact-1");
            assertTrue(retrieved.isPresent());
            assertTrue(retrieved.get().getAccessCount() >= 2);
            assertNotNull(retrieved.get().getLastAccessedAt());
        }
    }

    @Test
    void listReturnsAllEntriesOrdered(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("f1").content("内容1").build());
            store.store(AgentMemoryEntry.builder().id("f2").content("内容2").build());
            store.store(AgentMemoryEntry.builder().id("f3").content("内容3").build());

            List<AgentMemoryEntry> all = store.list(MemoryListQuery.builder().limit(100).build());
            assertEquals(3, all.size());
        }
    }

    @Test
    void listFiltersByStatus(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("active")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.ACTIVE).build());
            store.store(AgentMemoryEntry.builder().id("pending")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.PENDING).build());

            List<AgentMemoryEntry> activeOnly = store.list(MemoryListQuery.builder()
                    .status(AgentMemoryEntry.MemoryStatus.ACTIVE).limit(100).build());
            assertTrue(activeOnly.stream().allMatch(e -> e.getStatus() == AgentMemoryEntry.MemoryStatus.ACTIVE));
        }
    }

    @Test
    void statsReturnsCorrectCounts(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("f1").content("内容1").scope(AgentMemoryEntry.MemoryScope.PROJECT)
                    .project("/project/test").confidence(0.9)
                    .status(AgentMemoryEntry.MemoryStatus.ACTIVE).build());
            store.store(AgentMemoryEntry.builder()
                    .id("f2").content("内容2").scope(AgentMemoryEntry.MemoryScope.GLOBAL)
                    .confidence(0.5)
                    .status(AgentMemoryEntry.MemoryStatus.PENDING).build());

            MemoryStats stats = store.stats();
            assertEquals(2, stats.totalEntries());
            assertEquals(1, stats.activeEntries());
            assertEquals(1, stats.pendingEntries());
            assertEquals(1, stats.projectScopedEntries());
            assertEquals(1, stats.globalScopedEntries());
            assertEquals(0.7, stats.averageConfidence(), 0.001);
        }
    }

    @Test
    void recordUserQueryUpdatesVocabulary(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.recordUserQuery("SQLite 数据库存储");

            double boost = store.vocabularyBoost("SQLite");
            assertTrue(boost > 1.0, "用户提过的词 boost 应大于 1.0");
        }
    }

    @Test
    void vocabularyBoostReturnsOneForUnseenTerm(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            assertEquals(1.0, store.vocabularyBoost("从未提过的词"));
            assertEquals(1.0, store.vocabularyBoost(null));
            assertEquals(1.0, store.vocabularyBoost(""));
        }
    }

    @Test
    void cleanupExpiredRemovesExpiredEntries(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("active")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.ACTIVE).build());
            store.store(AgentMemoryEntry.builder().id("expired")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.EXPIRED).build());
            store.store(AgentMemoryEntry.builder().id("pending-expired")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.PENDING)
                    .pendingExpiresAt(Instant.parse("2020-01-01T00:00:00Z")).build());

            int deleted = store.cleanupExpired();
            assertEquals(2, deleted);
            assertEquals(1, store.size());
            assertTrue(store.retrieve("active").isPresent());
        }
    }

    @Test
    void findSimilarReturnsMatchingEntry(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder()
                    .id("sqlite-fact")
                    .content("项目使用 SQLite 作为本地存储数据库")
                    .keywords(List.of("SQLite", "数据库"))
                    .build());

            Optional<AgentMemoryEntry> similar = store.findSimilar(
                    "项目使用 SQLite 数据库", List.of("SQLite"), 0.0);
            assertTrue(similar.isPresent());
            assertEquals("sqlite-fact", similar.get().getId());
        }
    }

    @Test
    void findSimilarReturnsEmptyForBlankContent(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = createStore(memoryDir, "/project/test")) {
            store.store(AgentMemoryEntry.builder().id("f1").content("内容").build());

            assertTrue(store.findSimilar("", List.of(), 0.5).isEmpty());
            assertTrue(store.findSimilar(null, List.of(), 0.5).isEmpty());
        }
    }

    @Test
    void storePersistsAcrossReopen(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store1 = createStore(memoryDir, "/project/test")) {
            store1.store(AgentMemoryEntry.builder()
                    .id("persistent-fact")
                    .content("持久化测试内容")
                    .build());
        }

        try (SqliteAgentMemoryStore store2 = createStore(memoryDir, "/project/test")) {
            Optional<AgentMemoryEntry> retrieved = store2.retrieve("persistent-fact");
            assertTrue(retrieved.isPresent());
            assertEquals("持久化测试内容", retrieved.get().getContent());
        }
    }
}
