package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionMessageIndexerTest {

    @TempDir
    Path tempDir;

    private SqliteSessionMessageStore newStore() throws SQLException {
        return new SqliteSessionMessageStore(tempDir.toFile());
    }

    @Test
    void generateConversationIdProducesStablePrefix() {
        String id1 = SessionMessageIndexer.generateConversationId();
        String id2 = SessionMessageIndexer.generateConversationId();
        assertTrue(id1.startsWith("session_"));
        assertTrue(id2.startsWith("session_"));
        assertNotEquals(id1, id2);
    }

    @Test
    void indexIncrementalSyncWritesNewMessages() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.system("system prompt"));
            history.add(LlmClient.Message.user("如何配置 SQLite"));
            history.add(LlmClient.Message.assistant("使用 FTS5"));

            int count = indexer.indexIncrementalSync(history);
            assertEquals(2, count);  // system 被跳过
            assertEquals(2, store.size());
            assertEquals(1, store.conversationCount());
            assertEquals(3, indexer.getLastIndex());  // 已处理到 history 末尾
        }
    }

    @Test
    void indexIncrementalSyncOnlyWritesNewMessages() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user("第一轮问题"));
            history.add(LlmClient.Message.assistant("第一轮回答"));

            indexer.indexIncrementalSync(history);
            assertEquals(2, store.size());

            // 第二轮新增
            history.add(LlmClient.Message.user("第二轮问题"));
            history.add(LlmClient.Message.assistant("第二轮回答"));
            int count = indexer.indexIncrementalSync(history);
            assertEquals(2, count);  // 只写入新增的 2 条
            assertEquals(4, store.size());
        }
    }

    @Test
    void indexIncrementalSyncSkipsBlankContent() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user(""));  // 空内容
            history.add(LlmClient.Message.assistant("有效内容"));

            int count = indexer.indexIncrementalSync(history);
            assertEquals(1, count);  // 空内容被跳过
            assertEquals(1, store.size());
        }
    }

    @Test
    void indexIncrementalSyncSkipsSystemMessages() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.system("system1"));
            history.add(LlmClient.Message.system("system2"));
            history.add(LlmClient.Message.user("user content"));

            int count = indexer.indexIncrementalSync(history);
            assertEquals(1, count);
            assertEquals(1, store.size());
        }
    }

    @Test
    void indexIncrementalAsyncEventuallyWrites() throws Exception {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user("异步索引测试"));

            java.util.concurrent.CompletableFuture<Integer> future = indexer.indexIncremental(history);
            int count = future.get(5, TimeUnit.SECONDS);
            assertEquals(1, count);
            assertEquals(1, store.size());
        }
    }

    @Test
    void indexIncrementalReturnsZeroForNullStore() {
        SessionMessageIndexer indexer = new SessionMessageIndexer(null, "c1", "/proj");
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.user("test"));
        assertEquals(0, indexer.indexIncrementalSync(history));
    }

    @Test
    void indexIncrementalReturnsZeroForEmptyHistory() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            assertEquals(0, indexer.indexIncrementalSync(new ArrayList<>()));
            assertEquals(0, store.size());
        }
    }

    @Test
    void indexIncrementalGeneratesStableIds() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user("msg1"));
            history.add(LlmClient.Message.assistant("msg2"));

            indexer.indexIncrementalSync(history);
            // 重复调用不应产生新条目（幂等）
            indexer.indexIncrementalSync(history);
            assertEquals(2, store.size());
        }
    }

    @Test
    void closeShutsDownExecutor() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            indexer.close();
            // 关闭后再调用应该返回 0
            assertEquals(0, indexer.indexIncrementalSync(new ArrayList<>()));
        }
    }

    @Test
    void indexIncrementalPreservesToolMessages() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user("运行命令"));
            history.add(LlmClient.Message.assistant("执行中", List.of(
                    new com.bettercli.llm.LlmClient.ToolCall("call-1",
                            new com.bettercli.llm.LlmClient.ToolCall.Function("execute_command", "{\"cmd\":\"ls\"}"))
            )));
            history.add(LlmClient.Message.tool("call-1", "file1\nfile2"));

            int count = indexer.indexIncrementalSync(history);
            assertEquals(3, count);
            List<SessionMessage> conv = store.loadConversation("c1");
            assertEquals("tool", conv.get(2).getRole());
            assertEquals("call-1", conv.get(2).getToolCallId());
            assertNotNull(conv.get(1).getToolCallsJson());
        }
    }

    @Test
    void indexCompactedResetsCursorAndWritesSummary() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c1", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.user("old-1"));
            history.add(LlmClient.Message.assistant("old-a"));
            history.add(LlmClient.Message.user("old-2"));
            history.add(LlmClient.Message.assistant("old-b"));
            indexer.indexIncrementalSync(history);
            assertEquals(4, indexer.getLastIndex());

            // 模拟压缩后 history 缩短
            history.clear();
            history.add(LlmClient.Message.user("kept"));
            history.add(LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_PREFIX + "handoff"));
            var checkpoint = new ConversationHistoryCompactor.CompactCheckpoint(
                    CompactTrigger.MID_TURN,
                    ConversationHistoryCompactor.SUMMARY_PREFIX + "handoff about SQLite FTS",
                    List.copyOf(history)
            );
            int written = indexer.indexCompactedSync(checkpoint, history);
            assertTrue(written >= 1);
            assertEquals(2, indexer.getLastIndex());

            // 压缩后新增消息应能继续索引
            history.add(LlmClient.Message.user("after-compact"));
            int more = indexer.indexIncrementalSync(history);
            assertEquals(1, more);
            assertTrue(store.size() >= 5);

            var hits = store.search(SessionMessageSearchQuery.builder()
                    .query("handoff SQLite")
                    .limit(5)
                    .build());
            assertFalse(hits.isEmpty());
        }
    }

    @Test
    void indexCompactedIndexesPreTurnRetainUser() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "c2", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.system("S"));
            history.add(LlmClient.Message.user("old"));
            history.add(LlmClient.Message.assistant("a"));
            history.add(LlmClient.Message.user("CURRENT TURN unique-retain-token"));
            indexer.indexIncrementalSync(history);

            List<LlmClient.Message> after = new ArrayList<>();
            after.add(LlmClient.Message.system("S"));
            after.add(LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_PREFIX + "sum"));
            after.add(LlmClient.Message.user("CURRENT TURN unique-retain-token"));
            var checkpoint = new ConversationHistoryCompactor.CompactCheckpoint(
                    CompactTrigger.PRE_TURN,
                    ConversationHistoryCompactor.SUMMARY_PREFIX + "sum",
                    List.of(after.get(1), after.get(2))
            );
            indexer.indexCompactedSync(checkpoint, after);

            var hits = store.search(SessionMessageSearchQuery.builder()
                    .query("unique-retain-token")
                    .limit(5)
                    .build());
            assertFalse(hits.isEmpty());
        }
    }

    @Test
    void incrementalAfterShrinkRealignsInsteadOfNoOp() throws Exception {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "race", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                history.add(LlmClient.Message.user("msg-" + i));
            }
            indexer.indexIncrementalSync(history);
            assertEquals(6, indexer.getLastIndex());

            // 模拟 Mid-Turn 压缩缩短 history，但 end-of-turn 增量先于 compacted 完成时的旧游标
            List<LlmClient.Message> shrunk = new ArrayList<>();
            shrunk.add(LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_PREFIX + "sum"));
            shrunk.add(LlmClient.Message.user("after-tool-result unique-race-token"));
            int written = indexer.indexIncrementalSync(shrunk);
            assertTrue(written >= 1, "缩短后应换 epoch 重建索引，不能静默跳过");
            assertEquals(2, indexer.getLastIndex());

            var hits = store.search(SessionMessageSearchQuery.builder()
                    .query("unique-race-token")
                    .limit(5)
                    .build());
            assertFalse(hits.isEmpty());
        }
    }

    @Test
    void reindexFromStartRebuildsSearchableContent() throws SQLException {
        try (SqliteSessionMessageStore store = newStore()) {
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "resume", "/proj");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.system("sys"));
            history.add(LlmClient.Message.user("resume-unique-token alpha"));
            history.add(LlmClient.Message.assistant("resume-unique-token beta"));
            // 模拟错误地以为已索引完
            indexer.resetIndex(history.size());
            assertEquals(0, indexer.indexIncrementalSync(history));

            int written = indexer.reindexFromStart(history);
            assertTrue(written >= 2);
            var hits = store.search(SessionMessageSearchQuery.builder()
                    .query("resume-unique-token")
                    .limit(5)
                    .build());
            assertFalse(hits.isEmpty());
        }
    }

    @Test
    void indexBatchFailureDoesNotAdvanceCursor() {
        SessionMessageStore failing = new SessionMessageStore() {
            @Override
            public void index(SessionMessage message) {
                throw new RuntimeException("boom");
            }

            @Override
            public int indexBatch(List<SessionMessage> messages) {
                throw new RuntimeException("boom");
            }

            @Override
            public List<SessionMessageSearchResult> search(SessionMessageSearchQuery query) {
                return List.of();
            }

            @Override
            public List<SessionMessage> loadConversation(String conversationId) {
                return List.of();
            }

            @Override
            public List<String> listConversations(int limit) {
                return List.of();
            }

            @Override
            public int deleteConversation(String conversationId) {
                return 0;
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public int conversationCount() {
                return 0;
            }

            @Override
            public int migrateFromJsonl(java.io.File historyDir) {
                return 0;
            }

            @Override
            public void close() {
            }
        };
        SessionMessageIndexer indexer = new SessionMessageIndexer(failing, "fail", "/proj");
        List<LlmClient.Message> history = List.of(LlmClient.Message.user("should-retry-later"));
        assertEquals(0, indexer.indexIncrementalSync(history));
        assertEquals(0, indexer.getLastIndex(), "写库失败不得推进游标");
    }
}
