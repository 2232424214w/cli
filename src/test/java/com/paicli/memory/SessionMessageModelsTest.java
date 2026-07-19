package com.paicli.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionMessageModelsTest {

    @Test
    void sessionMessageBuilderProducesCorrectFields() {
        Instant now = Instant.now();
        SessionMessage msg = SessionMessage.builder()
                .id("msg-001")
                .conversationId("session_001")
                .role("user")
                .content("如何配置 SQLite FTS5")
                .project("/proj")
                .createdAt(now)
                .tokenCount(15)
                .build();

        assertEquals("msg-001", msg.getId());
        assertEquals("session_001", msg.getConversationId());
        assertEquals("user", msg.getRole());
        assertEquals("如何配置 SQLite FTS5", msg.getContent());
        assertEquals("/proj", msg.getProject());
        assertEquals(now, msg.getCreatedAt());
        assertEquals(15, msg.getTokenCount());
    }

    @Test
    void sessionMessageEstimatesTokensWhenTokenCountMissing() {
        SessionMessage msg = SessionMessage.builder()
                .id("m1")
                .conversationId("c1")
                .role("user")
                .content("hello world")
                .build();
        assertTrue(msg.getTokenCount() > 0);
    }

    @Test
    void sessionMessageHandlesNullContent() {
        SessionMessage msg = SessionMessage.builder()
                .id("m1")
                .conversationId("c1")
                .role("assistant")
                .content(null)
                .build();
        assertEquals("", msg.getContent());
        assertEquals(0, msg.getTokenCount());
    }

    @Test
    void sessionMessageDefaultsCreatedAtToNow() {
        Instant before = Instant.now().minusSeconds(1);
        SessionMessage msg = SessionMessage.builder()
                .id("m1")
                .conversationId("c1")
                .role("user")
                .content("test")
                .build();
        Instant after = Instant.now().plusSeconds(1);
        assertTrue(msg.getCreatedAt().isAfter(before));
        assertTrue(msg.getCreatedAt().isBefore(after));
    }

    @Test
    void sessionMessageSerializeToolCallsReturnsNullForEmpty() {
        assertNull(SessionMessage.serializeToolCalls(null));
        assertNull(SessionMessage.serializeToolCalls(List.of()));
    }

    @Test
    void sessionMessageSerializeToolCallsProducesJson() {
        String json = SessionMessage.serializeToolCalls(List.of("call1", "call2"));
        assertNotNull(json);
        assertTrue(json.contains("call1"));
        assertTrue(json.contains("call2"));
    }

    @Test
    void searchQueryDefaultsAreSane() {
        SessionMessageSearchQuery q = SessionMessageSearchQuery.builder()
                .query("test")
                .build();
        assertEquals("test", q.getQuery());
        assertEquals(3, q.getLimit());
        assertEquals(5, q.getTopKPerSession());
        assertEquals(500, q.getPreviewChars());
        assertNull(q.getRoleFilter());
        assertNull(q.getProject());
        assertNull(q.getSince());
        assertNull(q.getUntil());
    }

    @Test
    void searchQueryClampsLimitToRange() {
        // 0 / 负数视为未设置，使用默认值 3
        assertEquals(3, SessionMessageSearchQuery.builder().query("x").limit(0).build().getLimit());
        assertEquals(3, SessionMessageSearchQuery.builder().query("x").limit(-5).build().getLimit());
        assertEquals(3, SessionMessageSearchQuery.builder().query("x").limit(3).build().getLimit());
        assertEquals(10, SessionMessageSearchQuery.builder().query("x").limit(100).build().getLimit());
    }

    @Test
    void searchQueryClampsTopKPerSession() {
        // 0 / 负数视为未设置，使用默认值 5
        assertEquals(5, SessionMessageSearchQuery.builder().query("x").topKPerSession(0).build().getTopKPerSession());
        assertEquals(5, SessionMessageSearchQuery.builder().query("x").topKPerSession(-1).build().getTopKPerSession());
        assertEquals(20, SessionMessageSearchQuery.builder().query("x").topKPerSession(100).build().getTopKPerSession());
    }

    @Test
    void searchQueryClampsPreviewChars() {
        assertEquals(500, SessionMessageSearchQuery.builder().query("x").build().getPreviewChars());
        assertEquals(50, SessionMessageSearchQuery.builder().query("x").previewChars(10).build().getPreviewChars());
        assertEquals(10_000, SessionMessageSearchQuery.builder().query("x").previewChars(99_999).build().getPreviewChars());
    }

    @Test
    void searchQueryRetainsFilters() {
        Instant since = Instant.parse("2024-01-01T00:00:00Z");
        Instant until = Instant.parse("2024-12-31T23:59:59Z");
        SessionMessageSearchQuery q = SessionMessageSearchQuery.builder()
                .query("SQLite")
                .limit(5)
                .roleFilter("user")
                .project("/proj")
                .since(since)
                .until(until)
                .topKPerSession(10)
                .previewChars(200)
                .build();
        assertEquals(5, q.getLimit());
        assertEquals("user", q.getRoleFilter());
        assertEquals("/proj", q.getProject());
        assertEquals(since, q.getSince());
        assertEquals(until, q.getUntil());
        assertEquals(10, q.getTopKPerSession());
        assertEquals(200, q.getPreviewChars());
    }

    @Test
    void searchResultFormatConversationPreviewRendersRoles() {
        Instant t1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2024-01-01T00:01:00Z");
        List<SessionMessage> conv = List.of(
                SessionMessage.builder().id("m1").conversationId("c1").role("user")
                        .content("如何配置 SQLite").createdAt(t1).build(),
                SessionMessage.builder().id("m2").conversationId("c1").role("assistant")
                        .content("使用 FTS5 模块").createdAt(t2).build()
        );
        SessionMessageSearchResult result = new SessionMessageSearchResult(
                "c1", "/proj", t1, t2, 2, 0.85, List.of(), conv);

        String preview = result.formatConversationPreview();
        assertTrue(preview.contains("会话 c1"));
        assertTrue(preview.contains("[USER]: 如何配置 SQLite"));
        assertTrue(preview.contains("[ASSISTANT]: 使用 FTS5 模块"));
    }

    @Test
    void searchResultFormatConversationPreviewTruncatesLongContent() {
        String longContent = "a".repeat(600);
        List<SessionMessage> conv = List.of(
                SessionMessage.builder().id("m1").conversationId("c1").role("user")
                        .content(longContent).build()
        );
        SessionMessageSearchResult result = new SessionMessageSearchResult(
                "c1", null, Instant.now(), Instant.now(), 1, 0.5, List.of(), conv);
        String preview = result.formatConversationPreview();
        assertTrue(preview.contains("..."));
        assertTrue(preview.length() < 700);
    }

    @Test
    void searchResultFormatConversationPreviewHandlesEmptyConversation() {
        SessionMessageSearchResult result = new SessionMessageSearchResult(
                "c1", null, Instant.now(), Instant.now(), 0, 0.0, List.of(), List.of());
        assertEquals("(空会话)", result.formatConversationPreview());
    }

    @Test
    void searchResultGettersReturnImmutableLists() {
        List<SessionMessageSearchResult.MatchedMessage> matched = List.of(
                new SessionMessageSearchResult.MatchedMessage("m1", "user", "preview", 0.9, Instant.now())
        );
        List<SessionMessage> conv = List.of(
                SessionMessage.builder().id("m1").conversationId("c1").role("user").content("x").build()
        );
        SessionMessageSearchResult result = new SessionMessageSearchResult(
                "c1", "/p", Instant.now(), Instant.now(), 1, 0.9, matched, conv);

        assertEquals(1, result.getMatchedMessages().size());
        assertEquals(1, result.getFullConversation().size());
        assertThrows(UnsupportedOperationException.class, () -> result.getMatchedMessages().add(
                new SessionMessageSearchResult.MatchedMessage("m2", "user", "p", 0.5, Instant.now())));
        assertThrows(UnsupportedOperationException.class, () -> result.getFullConversation().add(
                SessionMessage.builder().id("m2").conversationId("c1").role("user").content("y").build()));
    }

    @Test
    void matchedMessageGetters() {
        Instant t = Instant.now();
        SessionMessageSearchResult.MatchedMessage m =
                new SessionMessageSearchResult.MatchedMessage("m1", "assistant", "preview text", 0.75, t);
        assertEquals("m1", m.getId());
        assertEquals("assistant", m.getRole());
        assertEquals("preview text", m.getPreview());
        assertEquals(0.75, m.getBm25Score());
        assertEquals(t, m.getCreatedAt());
    }
}
