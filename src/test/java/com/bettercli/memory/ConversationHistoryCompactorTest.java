package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorTest {

    @Test
    void dualConditionSkipsWhenMessageBodyTooSmall() {
        StubCompactor c = new StubCompactor("SUMMARY");
        List<LlmClient.Message> history = baseHistory(2, 100);
        CompactConfig config = new CompactConfig(100_000, 8_192, 20_000, 20_000, 5_000, 4_000, 90_000);

        assertFalse(c.needsCompaction(history, config, false));
        assertFalse(c.compact(history, CompactTrigger.PRE_TURN, config, 1));
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void preTurnKeepsCurrentUserMessageAndBuildsUserRoleCheckpoint() {
        StubCompactor c = new StubCompactor("HANDOFF SUMMARY");
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM"));
        history.add(LlmClient.Message.user("old-1 " + longText(12_000)));
        history.add(LlmClient.Message.assistant("a1 " + longText(12_000)));
        history.add(LlmClient.Message.user("old-2 " + longText(12_000)));
        history.add(LlmClient.Message.assistant("a2 " + longText(12_000)));
        history.add(LlmClient.Message.user("CURRENT TURN"));
        int currentIdx = history.size() - 1;

        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, 70_000);
        assertTrue(c.compact(history, CompactTrigger.PRE_TURN, config, currentIdx));

        assertEquals("system", history.get(0).role());
        assertTrue(history.stream().allMatch(m ->
                "system".equals(m.role()) || "user".equals(m.role())));
        assertTrue(history.get(history.size() - 1).content().contains("CURRENT TURN"));
        assertTrue(history.stream().anyMatch(m -> m.content() != null
                && m.content().contains(ConversationHistoryCompactor.SUMMARY_MARKER)
                && m.content().contains("HANDOFF SUMMARY")));
        assertFalse(history.stream().anyMatch(m -> "assistant".equals(m.role())));
    }

    @Test
    void midTurnCompressesEverythingIncludingToolResults() {
        StubCompactor c = new StubCompactor("MID SUMMARY");
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user("Q " + longText(10_000)));
        List<LlmClient.ToolCall> tcs = List.of(new LlmClient.ToolCall("c1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        history.add(LlmClient.Message.assistant(null, null, tcs));
        history.add(new LlmClient.Message("tool", "file " + longText(10_000), null, null, "c1"));

        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, 70_000);
        assertTrue(c.compact(history, CompactTrigger.MID_TURN, config, -1));

        assertEquals(1, c.summarizeCalls.get());
        assertEquals("system", history.get(0).role());
        assertTrue(history.stream().anyMatch(m -> m.content() != null
                && m.content().contains("MID SUMMARY")));
        assertFalse(history.stream().anyMatch(m -> "tool".equals(m.role())));
    }

    @Test
    void progressiveTrimRetriesWhenSummaryFailsThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                int n = attempts.incrementAndGet();
                if (n < 3) {
                    throw new IOException("prompt is too long");
                }
                return "OK AFTER TRIM";
            }
        };
        List<LlmClient.Message> history = baseHistory(8, 3_000);
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, null);
        assertTrue(c.compact(history, CompactTrigger.MANUAL, config, -1));
        assertTrue(attempts.get() >= 3);
        assertTrue(history.stream().anyMatch(m -> m.content() != null && m.content().contains("OK AFTER TRIM")));
    }

    @Test
    void emptySummaryFallsBackToHardTruncateCheckpoint() {
        StubCompactor c = new StubCompactor("   ");
        List<LlmClient.Message> history = baseHistory(5, 4_000);
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, null);
        assertTrue(c.compact(history, CompactTrigger.MANUAL, config, -1));
        assertTrue(history.get(1).content().contains(ConversationHistoryCompactor.HARD_TRUNCATE_MARKER)
                || history.stream().anyMatch(m -> m.content() != null
                && m.content().contains(ConversationHistoryCompactor.HARD_TRUNCATE_MARKER)));
    }

    @Test
    void filtersOldSummaryFromRecentUserMessages() {
        StubCompactor c = new StubCompactor("NEW");
        List<LlmClient.Message> toCompress = List.of(
                LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_PREFIX + "old"),
                LlmClient.Message.user("real-1"),
                LlmClient.Message.user("[反思提示] ignore"),
                LlmClient.Message.user("real-2")
        );
        List<LlmClient.Message> kept = c.extractRecentRealUserMessages(toCompress, 50_000);
        assertEquals(2, kept.size());
        assertEquals("real-1", kept.get(0).content());
        assertEquals("real-2", kept.get(1).content());
    }

    @Test
    void checkpointListenerReceivesSnapshot() {
        StubCompactor c = new StubCompactor("SNAP");
        AtomicReference<ConversationHistoryCompactor.CompactCheckpoint> seen = new AtomicReference<>();
        c.setCheckpointListener(seen::set);
        List<LlmClient.Message> history = baseHistory(4, 5_000);
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, null);
        assertTrue(c.compact(history, CompactTrigger.MANUAL, config, -1));
        assertNotNull(seen.get());
        assertEquals(CompactTrigger.MANUAL, seen.get().trigger());
        assertFalse(seen.get().replacementHistory().isEmpty());
    }

    @Test
    void sessionCheckpointStoreFastForwardOnLoad(@TempDir Path dir) {
        SessionCheckpointStore store = new SessionCheckpointStore(dir.resolve("session.jsonl"));
        store.appendMessage(LlmClient.Message.user("u1"));
        store.appendMessage(LlmClient.Message.assistant("a1"));
        store.appendCompacted(new ConversationHistoryCompactor.CompactCheckpoint(
                CompactTrigger.MID_TURN,
                ConversationHistoryCompactor.SUMMARY_PREFIX + "sum",
                List.of(
                        LlmClient.Message.user("kept-user"),
                        LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_PREFIX + "sum")
                )
        ));
        store.appendMessage(LlmClient.Message.user("after"));

        List<LlmClient.Message> loaded = store.loadHistory();
        assertEquals(3, loaded.size());
        assertEquals("kept-user", loaded.get(0).content());
        assertTrue(loaded.get(1).content().contains("sum"));
        assertEquals("after", loaded.get(2).content());
    }

    @Test
    void looksLikePromptTooLongDetectsCommonErrors() {
        assertTrue(ConversationHistoryCompactor.looksLikePromptTooLong(
                new IOException("API请求失败: prompt is too long")));
        assertTrue(ConversationHistoryCompactor.looksLikeContextWindowExceeded(
                new IOException("model_context_window_exceeded")));
        assertFalse(ConversationHistoryCompactor.looksLikePromptTooLong(new IOException("timeout")));
    }

    private static List<LlmClient.Message> baseHistory(int rounds, int chars) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM"));
        for (int i = 0; i < rounds; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(chars)));
            history.add(LlmClient.Message.assistant("A" + i + " " + longText(chars)));
        }
        return history;
    }

    private static String longText(int chars) {
        return "x".repeat(Math.max(0, chars));
    }

    private static class StubCompactor extends ConversationHistoryCompactor {
        final AtomicInteger summarizeCalls = new AtomicInteger();
        private final String mockSummary;

        StubCompactor(String mockSummary) {
            super(null);
            this.mockSummary = mockSummary;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) {
            summarizeCalls.incrementAndGet();
            return mockSummary;
        }
    }
}
