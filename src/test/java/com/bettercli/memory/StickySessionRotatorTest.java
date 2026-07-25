package com.bettercli.memory;

import com.bettercli.agent.Agent;
import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StickySessionRotatorTest {

    @TempDir
    Path tempDir;

    @Test
    void clearHistoryViaHookRotatesToEmptyCheckpointWithoutResumingOld() throws Exception {
        Path active = tempDir.resolve("active-session.id");
        SessionIdStore idStore = new SessionIdStore(active);
        String oldId = idStore.resolveOrCreate(true);
        Path oldPath = idStore.checkpointPathFor(oldId);
        SessionCheckpointStore oldStore = new SessionCheckpointStore(oldPath);
        oldStore.appendMessage(LlmClient.Message.user("should-not-resume"));

        Agent agent = new Agent(new StubClient());
        agent.setSessionCheckpointStore(oldStore);
        assertTrue(agent.getConversationHistory().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("should-not-resume")));

        AtomicReference<SessionCheckpointStore> storeRef = new AtomicReference<>(oldStore);
        AtomicReference<SessionMessageIndexer> indexerRef = new AtomicReference<>();
        StickySessionRotator rotator = new StickySessionRotator(
                idStore, null, storeRef, indexerRef, agent, () -> tempDir.toString());
        agent.setAfterClearHook(rotator::rotate);

        // 生产路径：只调 clearHistory，hook 负责轮换
        agent.clearHistory();

        List<LlmClient.Message> history = agent.getConversationHistory();
        assertEquals(1, history.size());
        assertEquals("system", history.get(0).role());
        assertTrue(history.stream().noneMatch(m -> m.content() != null && m.content().contains("should-not-resume")),
                "clear 后不得把旧检查点 Resume 回内存");

        assertTrue(Files.isRegularFile(oldPath), "旧 jsonl 应保留供审计");
        assertFalse(new SessionCheckpointStore(oldPath).loadHistory().isEmpty());
        assertTrue(storeRef.get().loadHistory().isEmpty(), "新会话检查点应为空");
        assertNotEquals(oldId, idStore.currentId());
    }

    @Test
    void forcedSessionIdClearTruncatesSameFileWithoutResume() throws Exception {
        Path active = tempDir.resolve("active-forced.id");
        String forced = "forced-session-id";
        System.setProperty("bettercli.session.id", forced);
        try {
            SessionIdStore idStore = new SessionIdStore(active);
            String id = idStore.resolveOrCreate(false);
            assertEquals(forced, id);
            Path path = idStore.checkpointPathFor(id);
            SessionCheckpointStore store = new SessionCheckpointStore(path);
            store.appendMessage(LlmClient.Message.user("stale-content"));

            Agent agent = new Agent(new StubClient());
            agent.setSessionCheckpointStore(store);
            AtomicReference<SessionCheckpointStore> storeRef = new AtomicReference<>(store);
            StickySessionRotator rotator = new StickySessionRotator(
                    idStore, null, storeRef, new AtomicReference<>(), agent, () -> tempDir.toString());
            agent.setAfterClearHook(rotator::rotate);

            agent.clearHistory();

            assertEquals(1, agent.getConversationHistory().size());
            assertTrue(agent.getConversationHistory().stream()
                    .noneMatch(m -> m.content() != null && m.content().contains("stale-content")));
            assertTrue(storeRef.get().loadHistory().isEmpty());
            assertTrue(Files.isRegularFile(path));
            assertEquals(0, Files.size(path));
        } finally {
            System.clearProperty("bettercli.session.id");
        }
    }

    private static final class StubClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "ok", null, 10, 1);
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

        @Override
        public int maxContextWindow() {
            return 128_000;
        }
    }
}
