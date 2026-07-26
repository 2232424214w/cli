package com.bettercli.agent;

import com.bettercli.llm.LlmClient;
import com.bettercli.skill.SkillContextBuffer;
import com.bettercli.subagent.CustomSubAgentCompletionNotice;
import com.bettercli.subagent.CustomSubAgentRegistry;
import com.bettercli.subagent.CustomSubAgentRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentClearHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    void clearHistoryRebuildsSystemPromptAndDropsPendingSkillContext() {
        String oldMemoryDir = System.getProperty("bettercli.memory.dir");
        System.setProperty("bettercli.memory.dir", tempDir.toString());
        try {
            RecordingClient llmClient = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "ok", null, 50_000, 1_000)
            ));
            Agent agent = new Agent(llmClient);
            SkillContextBuffer skillContextBuffer = new SkillContextBuffer();
            agent.setSkillContextBuffer(skillContextBuffer);
            agent.getMemoryManager().storeFact("CLEAR_MARKER should only appear when retrieved", "project");

            agent.run("CLEAR_MARKER");

            assertTrue(llmClient.firstSystemPrompt().contains("CLEAR_MARKER"),
                    "sanity check: the first turn should inject query-specific long-term memory");
            long beforeClearTokens = agent.currentStatus("idle").totalTokens();

            skillContextBuffer.push("demo", "pending skill body");
            agent.clearHistory();

            List<LlmClient.Message> history = agent.getConversationHistory();
            assertEquals(1, history.size());
            assertFalse(history.get(0).content().contains("CLEAR_MARKER"),
                    "/clear must not preserve the previous query's retrieved memory in system prompt");
            assertFalse(history.get(0).content().contains("## 相关长期记忆"));
            assertEquals("", skillContextBuffer.drain(), "/clear should drop pending skill injection");
            assertTrue(agent.currentStatus("idle").totalTokens() < beforeClearTokens,
                    "status ctx should reflect the cleared conversation instead of the previous LLM usage");
            assertTrue(agent.sessionEpoch() > 0, "/clear should bump sessionEpoch");
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("bettercli.memory.dir");
            } else {
                System.setProperty("bettercli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void clearHistoryQuietCancelsBackgroundAndBumpsEpoch(@TempDir Path agentsDir) throws Exception {
        Path user = agentsDir.resolve("user");
        Path dir = user.resolve("slow");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), """
                ---
                name: slow
                description: slow
                ---
                Finish.
                """);
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);

        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        LlmClient blockingClient = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return chat(messages, tools, StreamListener.NO_OP);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                entered.countDown();
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ChatResponse("assistant", "cancelled", null, 1, 1);
                }
                finished.set(true);
                return new ChatResponse("assistant", "done", null, 1, 1);
            }

            @Override
            public String getModelName() {
                return "test";
            }

            @Override
            public String getProviderName() {
                return "test";
            }

            @Override
            public boolean supportsTools() {
                return false;
            }
        };

        Agent agent = new Agent(new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "ok", null, 1, 1))));
        agent.setCustomSubAgentRunner(runner);
        agent.setFallbackConversationId("parent-clear");

        String accepted = runner.startAsync(
                "slow", "long", blockingClient, agent.getToolRegistry(), null, "parent-clear",
                true, null, agent.sessionEpoch());
        assertTrue(accepted.contains("CUSTOM_SUBAGENT_BG_ACCEPTED:"));
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        long epochBefore = agent.sessionEpoch();
        agent.clearHistory();

        assertTrue(agent.sessionEpoch() > epochBefore);
        assertEquals(1, agent.getConversationHistory().size());
        assertTrue(runner.activeRuns().isEmpty(), "quiet cancel should drop active runs");
        // 静默取消：不应再注入完成通知
        Thread.sleep(300);
        assertFalse(agent.getConversationHistory().stream().anyMatch(m ->
                m.content() != null && CustomSubAgentCompletionNotice.isCompletionNotice(m.content())));
        assertFalse(finished.get(), "cancelled worker should not finish cleanly");
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> capturedMessages = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            capturedMessages.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
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
            return 256_000;
        }

        private String firstSystemPrompt() {
            return capturedMessages.get(0).get(0).content();
        }
    }
}
