package com.paicli.memory;

import com.paicli.llm.LlmClient;
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
                    new com.paicli.llm.LlmClient.ToolCall("call-1",
                            new com.paicli.llm.LlmClient.ToolCall.Function("execute_command", "{\"cmd\":\"ls\"}"))
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
}
