package com.bettercli.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionSearchWindowTest {

    @Test
    void shortConversationNotTruncated() {
        List<SessionMessage> msgs = List.of(
                msg("user", "hello"),
                msg("assistant", "hi there")
        );
        String out = SessionSearchWindow.formatWithWindow(msgs, "hello", 100_000);
        assertTrue(out.contains("[USER]: hello"));
        assertTrue(out.contains("[ASSISTANT]: hi there"));
        assertFalse(out.contains("截断"));
    }

    @Test
    void centerTruncateKeepsKeywordNeighborhood() {
        String prefix = "A".repeat(1000);
        String hit = "UNIQUE_TOKEN_XYZ";
        String suffix = "B".repeat(1000);
        String text = prefix + hit + suffix;
        String sliced = SessionSearchWindow.centerTruncate(text, "UNIQUE_TOKEN_XYZ", 400);
        assertTrue(sliced.contains(hit), sliced);
        assertTrue(sliced.length() < text.length());
    }

    private static SessionMessage msg(String role, String content) {
        return new SessionMessage(
                "id-" + role,
                "conv-1",
                role,
                content,
                null,
                null,
                "/p",
                Instant.now(),
                10
        );
    }
}
