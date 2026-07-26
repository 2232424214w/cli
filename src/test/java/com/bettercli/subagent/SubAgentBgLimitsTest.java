package com.bettercli.subagent;

import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentBgLimitsTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("bettercli.subagent.bg.max.concurrent");
    }

    @Test
    void rejectsWhenBackgroundConcurrentLimitReached(@TempDir Path tempDir) throws Exception {
        System.setProperty("bettercli.subagent.bg.max.concurrent", "1");
        Path user = tempDir.resolve("user");
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
        LlmClient client = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return chat(messages, tools, StreamListener.NO_OP);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                entered.countDown();
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse("assistant", "done", null, 1, 1);
            }

            @Override
            public String getModelName() {
                return "t";
            }

            @Override
            public String getProviderName() {
                return "t";
            }

            @Override
            public boolean supportsTools() {
                return false;
            }
        };

        String first = runner.startAsync(
                "slow", "a", client, new ToolRegistry(), null, "p1", true, null, 0L);
        assertTrue(first.startsWith(CustomSubAgentRunner.BG_ACCEPTED_PREFIX), first);
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        String second = runner.startAsync(
                "slow", "b", client, new ToolRegistry(), null, "p1", true, null, 0L);
        assertTrue(second.contains("后台并发已达上限"), second);
        assertTrue(second.contains("1"), second);

        // 不同 parent 不受影响
        String other = runner.startAsync(
                "slow", "c", client, new ToolRegistry(), null, "p2", true, null, 0L);
        assertTrue(other.startsWith(CustomSubAgentRunner.BG_ACCEPTED_PREFIX), other);

        runner.cancelAllPending(false);
    }

    @Test
    void maxConcurrentZeroMeansUnlimited() {
        System.setProperty("bettercli.subagent.bg.max.concurrent", "0");
        assertEquals(0, SubAgentBgLimits.maxConcurrentBackground());
    }
}
