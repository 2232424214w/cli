package com.bettercli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqliteSessionMessageStoreTest {

    @TempDir
    Path tempDir;

    private SqliteSessionMessageStore newStore() throws SQLException {
        return new SqliteSessionMessageStore(tempDir.toFile());
    }

    private SessionMessage msg(String id, String conv, String role, String content, Instant t) {
        return SessionMessage.builder()
                .id(id).conversationId(conv).role(role).content(content)
                .project("/proj").createdAt(t).build();
    }

    // ==================== CRUD ====================

    @Test
    void indexInsertsMessage() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "如何配置 SQLite", Instant.now()));
            assertEquals(1, store.size());
            assertEquals(1, store.conversationCount());
        }
    }

    @Test
    void indexIsIdempotentForSameId() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessage m = msg("m1", "c1", "user", "test", Instant.now());
            store.index(m);
            store.index(m);
            assertEquals(1, store.size());
        }
    }

    @Test
    void indexBatchInsertsMultipleMessages() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            List<SessionMessage> batch = List.of(
                    msg("m1", "c1", "user", "hello", Instant.now()),
                    msg("m2", "c1", "assistant", "hi there", Instant.now()),
                    msg("m3", "c2", "user", "another session", Instant.now())
            );
            int count = store.indexBatch(batch);
            assertEquals(3, count);
            assertEquals(3, store.size());
            assertEquals(2, store.conversationCount());
        }
    }

    @Test
    void indexBatchSkipsDuplicateIds() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "first", Instant.now()));
            List<SessionMessage> batch = List.of(
                    msg("m1", "c1", "user", "duplicate id", Instant.now()),
                    msg("m2", "c1", "assistant", "new", Instant.now())
            );
            int count = store.indexBatch(batch);
            assertEquals(2, count);  // batch 尝试写入 2 条
            assertEquals(2, store.size());  // 实际只有 2 条（m1 保留原内容）
        }
    }

    @Test
    void loadConversationReturnsMessagesInTimeOrder() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
            Instant t2 = Instant.parse("2024-01-01T00:01:00Z");
            Instant t3 = Instant.parse("2024-01-01T00:02:00Z");
            store.index(msg("m2", "c1", "assistant", "second", t2));
            store.index(msg("m1", "c1", "user", "first", t1));
            store.index(msg("m3", "c1", "tool", "third", t3));

            List<SessionMessage> conv = store.loadConversation("c1");
            assertEquals(3, conv.size());
            assertEquals("m1", conv.get(0).getId());
            assertEquals("m2", conv.get(1).getId());
            assertEquals("m3", conv.get(2).getId());
        }
    }

    @Test
    void loadConversationReturnsEmptyForUnknownId() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            assertTrue(store.loadConversation("unknown").isEmpty());
        }
    }

    @Test
    void listConversationsReturnsSortedByLastActive() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "old", "user", "old", Instant.parse("2024-01-01T00:00:00Z")));
            store.index(msg("m2", "new", "user", "new", Instant.parse("2024-12-01T00:00:00Z")));
            store.index(msg("m3", "mid", "user", "mid", Instant.parse("2024-06-01T00:00:00Z")));

            List<String> convs = store.listConversations(10);
            assertEquals(3, convs.size());
            assertEquals("new", convs.get(0));
            assertEquals("mid", convs.get(1));
            assertEquals("old", convs.get(2));
        }
    }

    @Test
    void deleteConversationRemovesAllMessages() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "a", Instant.now()));
            store.index(msg("m2", "c1", "assistant", "b", Instant.now()));
            store.index(msg("m3", "c2", "user", "c", Instant.now()));

            int deleted = store.deleteConversation("c1");
            assertEquals(2, deleted);
            assertEquals(1, store.size());
            assertEquals(1, store.conversationCount());
            assertTrue(store.loadConversation("c1").isEmpty());
        }
    }

    @Test
    void sizeAndConversationCountReturnZeroForEmptyStore() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            assertEquals(0, store.size());
            assertEquals(0, store.conversationCount());
        }
    }

    // ==================== 检索五阶段管道 ====================

    @Test
    void searchReturnsEmptyForBlankQuery() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "SQLite 配置", Instant.now()));
            assertTrue(store.search(SessionMessageSearchQuery.builder().query("").build()).isEmpty());
            assertTrue(store.search(SessionMessageSearchQuery.builder().query("   ").build()).isEmpty());
        }
    }

    @Test
    void searchReturnsMatchingConversation() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "如何配置 SQLite FTS5 全文检索", Instant.now()));
            store.index(msg("m2", "c1", "assistant", "使用 FTS5 模块和 BM25 排序", Instant.now()));
            store.index(msg("m3", "c2", "user", "完全无关的对话关于天气", Instant.now()));

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite FTS5").limit(3).build());
            assertEquals(1, results.size());
            assertEquals("c1", results.get(0).getConversationId());
            assertEquals(2, results.get(0).getTotalMessages());
            assertTrue(results.get(0).getBestBm25Score() > 0);
        }
    }

    @Test
    void searchGroupsByConversationAndReturnsTopN() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            // c1 有 1 条命中
            store.index(msg("m1", "c1", "user", "SQLite 数据库配置", Instant.now()));
            // c2 有 2 条命中（应该排序更靠前）
            store.index(msg("m2", "c2", "user", "SQLite FTS5 配置", Instant.now()));
            store.index(msg("m3", "c2", "assistant", "SQLite BM25 检索", Instant.now()));
            // c3 无关
            store.index(msg("m4", "c3", "user", "天气真好", Instant.now()));

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite").limit(2).build());
            assertEquals(2, results.size());
            // c2 应该排第一（2 条命中）
            assertEquals("c2", results.get(0).getConversationId());
        }
    }

    @Test
    void searchFiltersByProject() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(SessionMessage.builder().id("m1").conversationId("c1").role("user")
                    .content("SQLite 配置").project("/proj-a").createdAt(Instant.now()).build());
            store.index(SessionMessage.builder().id("m2").conversationId("c2").role("user")
                    .content("SQLite 配置").project("/proj-b").createdAt(Instant.now()).build());

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite").project("/proj-a").build());
            assertEquals(1, results.size());
            assertEquals("c1", results.get(0).getConversationId());
        }
    }

    @Test
    void searchFiltersByRole() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "SQLite 配置", Instant.now()));
            store.index(msg("m2", "c1", "assistant", "SQLite 使用 FTS5", Instant.now()));

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite").roleFilter("user").build());
            assertEquals(1, results.size());
            // 只命中 user 消息
            assertEquals(1, results.get(0).getMatchedMessages().size());
            assertEquals("user", results.get(0).getMatchedMessages().get(0).getRole());
        }
    }

    @Test
    void searchFiltersByTimeRange() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "SQLite 配置", Instant.parse("2024-01-01T00:00:00Z")));
            store.index(msg("m2", "c2", "user", "SQLite 配置", Instant.parse("2024-06-01T00:00:00Z")));

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite")
                            .since(Instant.parse("2024-05-01T00:00:00Z"))
                            .until(Instant.parse("2024-12-31T00:00:00Z"))
                            .build());
            assertEquals(1, results.size());
            assertEquals("c2", results.get(0).getConversationId());
        }
    }

    @Test
    void searchReturnsFullConversationForHit() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            store.index(msg("m1", "c1", "user", "如何配置 SQLite", Instant.parse("2024-01-01T00:00:00Z")));
            store.index(msg("m2", "c1", "assistant", "使用 FTS5", Instant.parse("2024-01-01T00:01:00Z")));
            store.index(msg("m3", "c1", "tool", "result", Instant.parse("2024-01-01T00:02:00Z")));

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite").build());
            assertEquals(1, results.size());
            assertEquals(3, results.get(0).getFullConversation().size());
            assertEquals(3, results.get(0).getTotalMessages());
        }
    }

    @Test
    void searchPreviewTruncatesLongContent() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            String longContent = "SQLite 配置 " + "a".repeat(600);
            store.index(msg("m1", "c1", "user", longContent, Instant.now()));

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite").previewChars(100).build());
            assertEquals(1, results.size());
            String preview = results.get(0).getMatchedMessages().get(0).getPreview();
            assertTrue(preview.endsWith("..."));
            assertTrue(preview.length() <= 103);  // 100 + "..."
        }
    }

    @Test
    void searchRespectsLimit() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            for (int i = 0; i < 5; i++) {
                store.index(msg("m" + i, "c" + i, "user", "SQLite 配置 " + i, Instant.now()));
            }

            List<SessionMessageSearchResult> results = store.search(
                    SessionMessageSearchQuery.builder().query("SQLite").limit(3).build());
            assertEquals(3, results.size());
        }
    }

    // ==================== 从 jsonl 迁移 ====================

    @Test
    void migrateFromJsonlImportsMessages() throws SQLException, IOException {
        Path jsonl = tempDir.resolve("session_test1.jsonl");
        writeJsonlLine(jsonl, "user", "如何配置 SQLite", 1700000000000L);
        writeJsonlLine(jsonl, "assistant", "使用 FTS5 模块", 1700000060000L);

        try (SqliteSessionMessageStore store = newStore()) {
            int migrated = store.migrateFromJsonl(tempDir.toFile());
            assertEquals(2, migrated);
            assertEquals(2, store.size());
            assertEquals(1, store.conversationCount());

            List<SessionMessage> conv = store.loadConversation("session_test1");
            assertEquals(2, conv.size());
            assertEquals("user", conv.get(0).getRole());
            assertEquals("assistant", conv.get(1).getRole());
        }
    }

    @Test
    void migrateFromJsonlIsIdempotent() throws SQLException, IOException {
        Path jsonl = tempDir.resolve("session_test1.jsonl");
        writeJsonlLine(jsonl, "user", "SQLite 配置", 1700000000000L);

        try (SqliteSessionMessageStore store = newStore()) {
            int first = store.migrateFromJsonl(tempDir.toFile());
            int second = store.migrateFromJsonl(tempDir.toFile());
            assertEquals(1, first);
            assertEquals(0, second);
            assertEquals(1, store.size());
        }
    }

    @Test
    void migrateFromJsonlWritesMarkerFile() throws SQLException, IOException {
        Path jsonl = tempDir.resolve("session_test1.jsonl");
        writeJsonlLine(jsonl, "user", "SQLite", 1700000000000L);

        try (SqliteSessionMessageStore store = newStore()) {
            store.migrateFromJsonl(tempDir.toFile());
            assertTrue(tempDir.resolve(".session-messages-migrated").toFile().exists());
        }
    }

    @Test
    void migrateFromJsonlHandlesMultipleFiles() throws SQLException, IOException {
        Path f1 = tempDir.resolve("session_a.jsonl");
        Path f2 = tempDir.resolve("session_b.jsonl");
        writeJsonlLine(f1, "user", "SQLite 配置", 1700000000000L);
        writeJsonlLine(f2, "user", "另一个会话", 1700000060000L);

        try (SqliteSessionMessageStore store = newStore()) {
            int migrated = store.migrateFromJsonl(tempDir.toFile());
            assertEquals(2, migrated);
            assertEquals(2, store.conversationCount());
        }
    }

    @Test
    void migrateFromJsonlReturnsZeroForMissingDir() throws SQLException, IOException {
        try (SqliteSessionMessageStore store = newStore()) {
            assertEquals(0, store.migrateFromJsonl(tempDir.resolve("nonexistent").toFile()));
        }
    }

    @Test
    void migrateFromJsonlSkipsUnparseableLines() throws SQLException, IOException {
        Path jsonl = tempDir.resolve("session_test1.jsonl");
        Files.writeString(jsonl, "not a json line\n");
        writeJsonlLine(jsonl, "user", "SQLite 配置", 1700000000000L);

        try (SqliteSessionMessageStore store = newStore()) {
            int migrated = store.migrateFromJsonl(tempDir.toFile());
            assertEquals(1, migrated);
        }
    }

    private void writeJsonlLine(Path file, String role, String content, long timestamp) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("role", role);
        record.put("content", content);
        record.put("timestamp", timestamp);
        record.put("metadata", new LinkedHashMap<>());
        String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(record);
        Files.writeString(file, line + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
