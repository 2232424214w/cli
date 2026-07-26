package com.bettercli.memory;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class SessionSearchSummarizerTest {

    @Test
    void nullLlmDegradesToPreview() {
        SessionMessageSearchResult result = sampleResult();
        List<SessionSearchSummarizer.SessionSummary> summaries =
                SessionSearchSummarizer.summarizeAll(List.of(result), "Java", null, 5);
        assertEquals(1, summaries.size());
        assertEquals("degraded", summaries.get(0).status());
        assertTrue(summaries.get(0).summary().contains("Java")
                || summaries.get(0).summary().contains("USER"));
    }

    @Test
    void llmSuccessReturnsOk() {
        StubClient llm = new StubClient(List.of(
                new LlmClient.ChatResponse("assistant", "用户想确认 Java 版本，结论是 17。", null, 10, 5)
        ));
        SessionMessageSearchResult result = sampleResult();
        List<SessionSearchSummarizer.SessionSummary> summaries =
                SessionSearchSummarizer.summarizeAll(List.of(result), "Java", llm, 10);
        assertEquals(1, summaries.size());
        assertEquals("ok", summaries.get(0).status());
        assertTrue(summaries.get(0).summary().contains("Java"));
    }

    private static SessionMessageSearchResult sampleResult() {
        SessionMessage user = new SessionMessage(
                "m1", "conv-a", "user", "项目用 Java 吗？", null, null, "/p", Instant.now(), 8);
        SessionMessage asst = new SessionMessage(
                "m2", "conv-a", "assistant", "是的，Java 17。", null, null, "/p", Instant.now(), 8);
        return new SessionMessageSearchResult(
                "conv-a", "/p", Instant.now(), Instant.now(), 2, 1.2,
                List.of(new SessionMessageSearchResult.MatchedMessage(
                        "m1", "user", "项目用 Java 吗？", 1.2, Instant.now())),
                List.of(user, asst)
        );
    }

    private static final class StubClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            ChatResponse r = responses.poll();
            if (r == null) {
                throw new IOException("no response");
            }
            return r;
        }
    }
}
