package com.bettercli.memory;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class AutoExtractFactsTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearFlag() {
        System.clearProperty("bettercli.memory.auto_extract.enabled");
    }

    @Test
    void extractFactCandidatesFiltersEphemeral() {
        StubClient llm = new StubClient(List.of(
                new LlmClient.ChatResponse("assistant",
                        "- 用户偏好使用 Java\n- 明天再改\n- 也许用 Kotlin\n", null, 10, 5)
        ));
        ContextCompressor compressor = new ContextCompressor(llm);
        List<MemoryEntry> entries = List.of(
                new MemoryEntry("user-1", "我平时用 Java", MemoryEntry.MemoryType.CONVERSATION,
                        java.util.Map.of("source", "user"), 10),
                new MemoryEntry("assistant-1", "好的", MemoryEntry.MemoryType.CONVERSATION,
                        java.util.Map.of("source", "assistant"), 5)
        );
        List<String> facts = compressor.extractFactCandidates(entries);
        assertTrue(facts.stream().anyMatch(f -> f.contains("Java")));
        assertFalse(facts.stream().anyMatch(f -> f.contains("明天")));
        assertFalse(facts.stream().anyMatch(f -> f.contains("也许")));
    }

    @Test
    void compressTriggersAutoExtractWhenEnabled() {
        System.setProperty("bettercli.memory.auto_extract.enabled", "true");
        StubClient llm = new StubClient(List.of(
                // extractFacts
                new LlmClient.ChatResponse("assistant", "- 用户偏好深色主题\n", null, 10, 5),
                // compress map/reduce may need more responses
                new LlmClient.ChatResponse("assistant", "摘要", null, 10, 5),
                new LlmClient.ChatResponse("assistant", "摘要", null, 10, 5),
                new LlmClient.ChatResponse("assistant", "摘要", null, 10, 5)
        ));
        LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
        MemoryManager mm = new MemoryManager(llm, 40, 128000, ltm);
        mm.setProjectPath("/repo/demo");

        String longMessage = "a".repeat(36);
        mm.addUserMessage(longMessage);
        mm.addAssistantMessage(longMessage);
        mm.addUserMessage(longMessage);
        mm.addAssistantMessage(longMessage);

        assertTrue(ltm.getAll().stream().anyMatch(e ->
                e.getContent().contains("深色") && "fact_extractor".equals(e.getMetadata().get("source"))),
                "压缩前应写入自动提取的事实");
    }

    @Test
    void autoExtractDisabledByDefault() {
        assertFalse(MemoryManager.isAutoExtractEnabled());
    }

    private static final class StubClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            ChatResponse next = responses.poll();
            if (next == null) {
                return new ChatResponse("assistant", "ok", null, 1, 1);
            }
            return next;
        }
    }
}
