package com.bettercli.agent;

import com.bettercli.llm.LlmClient;
import com.bettercli.memory.ConversationHistoryCompactor;
import com.bettercli.memory.SessionCheckpointStore;
import com.bettercli.memory.SessionMessageIndexer;
import com.bettercli.memory.SessionMessageSearchQuery;
import com.bettercli.memory.SqliteSessionMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class AgentCompactionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void compactHistoryNowPersistsCheckpointAndIndexesSummary() throws SQLException {
        String oldMemoryDir = System.getProperty("bettercli.memory.dir");
        System.setProperty("bettercli.memory.dir", tempDir.resolve("mem").toString());
        try (SqliteSessionMessageStore store = new SqliteSessionMessageStore(tempDir.resolve("db").toFile())) {
            ScriptedClient llm = new ScriptedClient(new ArrayDeque<>());
            // compactHistoryNow → ConversationHistoryCompactor.summarize 会调一次 LLM
            llm.enqueue(new LlmClient.ChatResponse("assistant",
                    "【任务目标】压测\n【关键约束】无\n【当前进展】已灌长文\n【未完成待办】无\n【关键数据】无",
                    null, 100, 50));

            Agent agent = new Agent(llm);
            SessionCheckpointStore checkpointStore = new SessionCheckpointStore(tempDir.resolve("session.jsonl"));
            SessionMessageIndexer indexer = new SessionMessageIndexer(store, "agent-compact", tempDir.toString());
            agent.setSessionMessageIndexer(indexer);
            agent.setSessionCheckpointStore(checkpointStore);

            List<LlmClient.Message> history = agent.getConversationHistory();
            for (int i = 0; i < 6; i++) {
                history.add(LlmClient.Message.user("Q" + i + " " + "x".repeat(8_000)));
                history.add(LlmClient.Message.assistant("A" + i + " " + "y".repeat(8_000)));
            }

            Agent.CompactionResult result = agent.compactHistoryNow();
            assertTrue(result.compacted(), "MANUAL compact should rewrite history");
            assertTrue(result.afterTokens() < result.beforeTokens());

            List<LlmClient.Message> after = agent.getConversationHistory();
            assertTrue(after.stream().anyMatch(m -> m.content() != null
                    && m.content().contains(ConversationHistoryCompactor.SUMMARY_MARKER)));

            List<LlmClient.Message> resumed = checkpointStore.loadHistory();
            assertFalse(resumed.isEmpty());
            assertTrue(resumed.stream().anyMatch(m -> m.content() != null
                    && m.content().contains(ConversationHistoryCompactor.SUMMARY_MARKER)));

            var hits = store.search(SessionMessageSearchQuery.builder()
                    .query("压测")
                    .limit(5)
                    .build());
            assertFalse(hits.isEmpty(), "/compact 后摘要应同步可被 session_search 检索");
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("bettercli.memory.dir");
            } else {
                System.setProperty("bettercli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void overflowRetryCompactsOnceThenContinues(@TempDir Path dir) {
        String oldMemoryDir = System.getProperty("bettercli.memory.dir");
        System.setProperty("bettercli.memory.dir", dir.resolve("mem").toString());
        try {
            ScriptedClient llm = new ScriptedClient(new ArrayDeque<>());
            llm.enqueueError(new IOException("API请求失败: prompt is too long"));
            llm.enqueue(new LlmClient.ChatResponse("assistant",
                    "【任务目标】overflow\n【关键约束】无\n【当前进展】已压\n【未完成待办】无\n【关键数据】无",
                    null, 100, 40));
            llm.enqueue(new LlmClient.ChatResponse("assistant", "recovered after compact", null, 1_000, 10));

            Agent agent = new Agent(llm);
            String answer = agent.run("please continue");
            assertTrue(answer == null
                            || answer.isBlank()
                            || answer.contains("recovered after compact")
                            || answer.contains("recovered"),
                    "overflow compact-and-retry should recover: " + answer);
            assertTrue(agent.getConversationHistory().stream().anyMatch(m ->
                    m.content() != null && m.content().contains(ConversationHistoryCompactor.SUMMARY_MARKER)));
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("bettercli.memory.dir");
            } else {
                System.setProperty("bettercli.memory.dir", oldMemoryDir);
            }
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<Object> script;

        private ScriptedClient(Queue<Object> script) {
            this.script = script;
        }

        void enqueue(ChatResponse response) {
            script.add(response);
        }

        void enqueueError(IOException error) {
            script.add(error);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            Object next = script.poll();
            if (next == null) {
                throw new IOException("缺少预设响应");
            }
            if (next instanceof IOException error) {
                throw error;
            }
            return (ChatResponse) next;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            // 较小窗口，便于溢出/压缩触发
            return 64_000;
        }

        @Override
        public boolean supportsTools() {
            return false;
        }
    }
}
