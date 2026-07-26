package com.bettercli.wechat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMessageQueueTest {

    @Test
    void fifoSameConversationAndIsolatedKeys() {
        ConversationMessageQueue<String> q = new ConversationMessageQueue<>();
        var a1 = q.enqueue("wechat-a", "m1");
        var a2 = q.enqueue("wechat-a", "m2");
        var b1 = q.enqueue("wechat-b", "x");

        assertTrue(a1.isHead());
        assertEquals(1, a1.position());
        assertFalse(a2.isHead());
        assertEquals(2, a2.position());
        assertTrue(b1.isHead());

        assertEquals("m1", q.dequeue("wechat-a").orElseThrow().payload());
        assertEquals("m2", q.peek("wechat-a").orElseThrow().payload());
        assertEquals("x", q.dequeue("wechat-b").orElseThrow().payload());
        assertTrue(q.dequeue("wechat-b").isEmpty());
    }

    @Test
    void removeFromMiddleDoesNotBlockTail() {
        ConversationMessageQueue<String> q = new ConversationMessageQueue<>();
        var first = q.enqueue("c", "1");
        var mid = q.enqueue("c", "2");
        q.enqueue("c", "3");

        assertTrue(q.remove("c", mid.ticket().entryId()));
        assertEquals(2, q.size("c"));
        assertEquals("1", q.dequeue("c").orElseThrow().payload());
        assertEquals("3", q.dequeue("c").orElseThrow().payload());
        assertFalse(q.remove("c", first.ticket().entryId()));
    }

    @Test
    void removeExpiredFromAnyPosition() {
        ConversationMessageQueue<String> q = new ConversationMessageQueue<>();
        var old = q.enqueue("c", "old");
        Instant later = old.ticket().enqueuedAt().plusSeconds(10);
        List<ConversationMessageQueue.Ticket<String>> expired =
                q.removeExpired("c", Duration.ofSeconds(5), later);
        assertEquals(1, expired.size());
        assertEquals("old", expired.get(0).payload());
        assertTrue(q.isEmpty("c"));

        q.enqueue("c", "fresh");
        assertTrue(q.removeExpired("c", Duration.ofSeconds(5), Instant.now()).isEmpty());
        assertEquals("fresh", q.peek("c").orElseThrow().payload());
    }

    @Test
    void blankConversationFallsBackToDefaultKey() {
        ConversationMessageQueue<String> q = new ConversationMessageQueue<>();
        q.enqueue(null, "a");
        q.enqueue("  ", "b");
        assertEquals(2, q.size("_default"));
        assertEquals("a", q.dequeue(null).orElseThrow().payload());
    }

    @Test
    void clearDropsAllPending() {
        ConversationMessageQueue<String> q = new ConversationMessageQueue<>();
        q.enqueue("c", "1");
        q.enqueue("c", "2");
        assertEquals(2, q.clear("c"));
        assertTrue(q.isEmpty("c"));
        assertEquals(Optional.empty(), q.peek("c"));
    }

    @Test
    void ticketCarriesEnqueueInstant() {
        Instant before = Instant.now().minusSeconds(1);
        ConversationMessageQueue<String> q = new ConversationMessageQueue<>();
        var r = q.enqueue("c", "x");
        assertTrue(r.ticket().enqueuedAt().isAfter(before)
                || r.ticket().enqueuedAt().equals(before));
        assertTrue(r.ticket().entryId().startsWith("q_"));
    }
}
