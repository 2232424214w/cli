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
        CompactConfig config = new CompactConfig(100_000, 8_192, 20_000, 20_000, 5_000, 4_000, 90_000, 0);

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

        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, 70_000, 0);
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

        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, 70_000, 0);
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
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, null, 0);
        assertTrue(c.compact(history, CompactTrigger.MANUAL, config, -1));
        assertTrue(attempts.get() >= 3);
        assertTrue(history.stream().anyMatch(m -> m.content() != null && m.content().contains("OK AFTER TRIM")));
    }

    @Test
    void emptySummaryFallsBackToHardTruncateCheckpoint() {
        StubCompactor c = new StubCompactor("   ");
        List<LlmClient.Message> history = baseHistory(5, 4_000);
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, null, 0);
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
    void pinsEarliestRealUserAsTaskSeedUnderBudget() {
        StubCompactor c = new StubCompactor("NEW");
        List<LlmClient.Message> toCompress = List.of(
                LlmClient.Message.user("TASK-GOAL fix login"),
                LlmClient.Message.assistant("ok"),
                LlmClient.Message.user("mid note"),
                LlmClient.Message.user("latest ask")
        );
        // 预算只够种子 + 最近一条
        int seedTokens = TokenBudget.estimateMessagesTokens(List.of(toCompress.get(0)));
        int latestTokens = TokenBudget.estimateMessagesTokens(List.of(toCompress.get(3)));
        List<LlmClient.Message> kept = c.extractRecentRealUserMessages(
                toCompress, seedTokens + latestTokens);
        assertEquals(2, kept.size());
        assertEquals("TASK-GOAL fix login", kept.get(0).content());
        assertEquals("latest ask", kept.get(1).content());
    }

    @Test
    void dropOldestTurnRemovesUserAndFollowingToolPair() {
        List<LlmClient.Message> working = new ArrayList<>();
        working.add(LlmClient.Message.user("u0"));
        working.add(LlmClient.Message.assistant("a0", List.of(
                new LlmClient.ToolCall("1", new LlmClient.ToolCall.Function("grep", "{\"q\":\"x\"}")))));
        working.add(LlmClient.Message.tool("1", "hits"));
        working.add(LlmClient.Message.user("u1"));
        ConversationHistoryCompactor.dropOldestTurn(working);
        assertEquals(1, working.size());
        assertEquals("u1", working.get(0).content());
    }

    @Test
    void summaryInputCharLimitScalesWithWindow() {
        CompactConfig tiny = new CompactConfig(16_000, 8_192, 20_000, 1_000, 5_000, 4_000, null, 0);
        CompactConfig large = new CompactConfig(200_000, 8_192, 20_000, 1_000, 5_000, 6_000, null, 0);
        int tinyLimit = ConversationHistoryCompactor.summaryInputCharLimit(tiny);
        int largeLimit = ConversationHistoryCompactor.summaryInputCharLimit(large);
        assertTrue(tinyLimit >= 8_000);
        assertTrue(largeLimit > tinyLimit);
        assertTrue(largeLimit <= 60_000);
    }

    @Test
    void checkpointListenerReceivesSnapshot() {
        StubCompactor c = new StubCompactor("SNAP");
        AtomicReference<ConversationHistoryCompactor.CompactCheckpoint> seen = new AtomicReference<>();
        c.setCheckpointListener(seen::set);
        List<LlmClient.Message> history = baseHistory(4, 5_000);
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, null, 0);
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
    void preTurnCheckpointPersistsRetainCurrentUserForResume(@TempDir Path dir) {
        StubCompactor c = new StubCompactor("PRE SUMMARY");
        SessionCheckpointStore store = new SessionCheckpointStore(dir.resolve("session.jsonl"));
        c.setCheckpointListener(store::appendCompacted);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM"));
        history.add(LlmClient.Message.user("old-1 " + longText(12_000)));
        history.add(LlmClient.Message.assistant("a1 " + longText(12_000)));
        history.add(LlmClient.Message.user("CURRENT TURN keep-me"));
        // 模拟 Agent：先落盘当前用户，再 Pre-Turn 压缩
        store.appendMessage(history.get(history.size() - 1));
        int currentIdx = history.size() - 1;
        CompactConfig config = new CompactConfig(80_000, 8_192, 20_000, 1_000, 5_000, 4_000, 70_000, 0);
        assertTrue(c.compact(history, CompactTrigger.PRE_TURN, config, currentIdx));

        List<LlmClient.Message> loaded = store.loadHistory();
        assertTrue(loaded.stream().anyMatch(m -> m.content() != null && m.content().contains("CURRENT TURN keep-me")),
                "Resume 必须保留 Pre-Turn retain 的当前用户消息");
        assertTrue(loaded.stream().anyMatch(m -> m.content() != null
                && m.content().contains(ConversationHistoryCompactor.SUMMARY_MARKER)));
    }

    @Test
    void sessionRotateDropsOnlyPreCompactedKeepsEntireTail(@TempDir Path dir) {
        SessionCheckpointStore store = new SessionCheckpointStore(dir.resolve("session.jsonl"), 5);
        store.appendMessage(LlmClient.Message.user("before"));
        store.appendCompacted(new ConversationHistoryCompactor.CompactCheckpoint(
                CompactTrigger.MANUAL,
                ConversationHistoryCompactor.SUMMARY_PREFIX + "sum",
                List.of(LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_MARKER + " sum"))
        ));
        for (int i = 0; i < 20; i++) {
            store.appendMessage(LlmClient.Message.user("tail-" + i));
        }
        List<LlmClient.Message> loaded = store.loadHistory();
        assertFalse(loaded.stream().anyMatch(m -> "before".equals(m.content())),
                "检查点之前的 raw 行应被 rotate 丢掉");
        assertTrue(loaded.stream().anyMatch(m -> m.content() != null
                && m.content().contains(ConversationHistoryCompactor.SUMMARY_MARKER)));
        assertEquals("tail-0", loaded.get(1).content(), "compacted 之后的早期消息不得被截断");
        assertEquals("tail-19", loaded.get(loaded.size() - 1).content());
        assertEquals(21, loaded.size());
    }

    @Test
    void sessionPhysicalMergeWhenHeadIsCompactedAndFileStillGrows(@TempDir Path dir) throws Exception {
        // 构造器会把 keepLines clamp 到 >=50；首行已是 compacted 时需 size > keep*2 才物理合并
        int keep = 50;
        SessionCheckpointStore store = new SessionCheckpointStore(dir.resolve("session.jsonl"), keep);
        store.appendCompacted(new ConversationHistoryCompactor.CompactCheckpoint(
                CompactTrigger.MID_TURN,
                ConversationHistoryCompactor.SUMMARY_PREFIX + "seed",
                List.of(LlmClient.Message.user(ConversationHistoryCompactor.SUMMARY_PREFIX + "seed"))
        ));
        int appendCount = keep * 2 + 1;
        for (int i = 0; i < appendCount; i++) {
            store.appendMessage(LlmClient.Message.user("keep-" + i));
        }
        List<LlmClient.Message> loaded = store.loadHistory();
        assertTrue(loaded.stream().anyMatch(m -> m.content() != null && m.content().contains("seed")));
        assertTrue(loaded.stream().anyMatch(m -> "keep-0".equals(m.content())));
        assertTrue(loaded.stream().anyMatch(m -> ("keep-" + (appendCount - 1)).equals(m.content())));
        long lines = java.nio.file.Files.readAllLines(dir.resolve("session.jsonl")).size();
        assertTrue(lines < appendCount + 1,
                "首行 compacted 后膨胀应触发物理合并，文件行数应下降: " + lines);
    }

    @Test
    void looksLikePromptTooLongDetectsCommonErrors() {
        assertTrue(ConversationHistoryCompactor.looksLikePromptTooLong(
                new IOException("API请求失败: prompt is too long")));
        assertTrue(ConversationHistoryCompactor.looksLikeContextWindowExceeded(
                new IOException("model_context_window_exceeded")));
        assertFalse(ConversationHistoryCompactor.looksLikePromptTooLong(new IOException("timeout")));
    }

    @Test
    void resolveTotalTokensPrefersMaxOfUsageAndEstimateWithSchema() {
        StubCompactor c = new StubCompactor("x");
        List<LlmClient.Message> history = baseHistory(3, 2_000);
        // 过期 usage（工具前）明显小于当前估算+schema
        CompactConfig staleUsage = new CompactConfig(
                80_000, 8_192, 20_000, 1_000, 5_000, 4_000, 5_000, 40_000);
        int resolved = c.resolveTotalTokens(history, staleUsage);
        int estimated = TokenBudget.estimateMessagesTokens(history) + 40_000;
        assertEquals(estimated, resolved);
        assertTrue(resolved > 5_000);
    }

    @Test
    void schemaTokensCanTriggerCompactionWhenUsageAloneWouldNot() {
        StubCompactor c = new StubCompactor("SCHEMA SUMMARY");
        List<LlmClient.Message> history = baseHistory(4, 4_000);
        int body = TokenBudget.estimateMessagesTokens(history.subList(1, history.size()));
        assertTrue(body >= 1_000);
        // lastKnown 低于可用上限；加上大 schema 后应超过
        int available = 80_000 - 20_000 - 8_192;
        CompactConfig config = new CompactConfig(
                80_000, 8_192, 20_000, 1_000, 5_000, 4_000,
                available - 1_000,
                50_000);
        assertTrue(c.needsCompaction(history, config, false));
        assertTrue(c.compact(history, CompactTrigger.MID_TURN, config, -1));
    }

    @Test
    void sessionCheckpointStoreRotatesOnAppendWhenOverLimit(@TempDir Path dir) throws Exception {
        // SessionCheckpointStore 把 keepLines clamp 到 >=50；无 compacted 时保留末尾 keep 行
        int keep = 50;
        SessionCheckpointStore store = new SessionCheckpointStore(dir.resolve("session.jsonl"), keep);
        for (int i = 0; i < keep + 10; i++) {
            store.appendMessage(LlmClient.Message.user("u" + i));
        }
        List<LlmClient.Message> loaded = store.loadHistory();
        assertTrue(loaded.size() <= keep);
        assertEquals("u" + (keep + 9), loaded.get(loaded.size() - 1).content());
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
