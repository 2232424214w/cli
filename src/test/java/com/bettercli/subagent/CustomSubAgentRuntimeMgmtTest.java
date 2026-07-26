package com.bettercli.subagent;

import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentRuntimeMgmtTest {

    @Test
    void steerServiceDrainClearsQueue() {
        AgentSteerService svc = new AgentSteerService();
        svc.enqueue("s1", "go left");
        svc.enqueue("s1", "then right");
        assertEquals(2, svc.pendingCount("s1"));
        List<String> drained = svc.drain("s1");
        assertEquals(List.of("go left", "then right"), drained);
        assertEquals(0, svc.pendingCount("s1"));
        assertTrue(svc.drain("s1").isEmpty());
    }

    @Test
    void terminateAndSteerOnRunningBackground(@TempDir Path tempDir) throws Exception {
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
        AtomicBoolean sawSteer = new AtomicBoolean(false);
        LlmClient client = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return chat(messages, tools, StreamListener.NO_OP);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                entered.countDown();
                for (Message m : messages) {
                    if (m.content() != null && m.content().contains("[steer")) {
                        sawSteer.set(true);
                    }
                }
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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

        String accepted = runner.startAsync(
                "slow", "long task", client, new ToolRegistry(), null, "parent-root", true, null);
        String sessionId = CustomSubAgentRunner.parseBgAcceptedSessionId(accepted);
        assertNotNull(sessionId);
        assertTrue(entered.await(3, TimeUnit.SECONDS));

        String tree = runner.formatRunningTree("parent-root");
        assertTrue(tree.contains(sessionId), tree);
        assertTrue(tree.contains("slow"), tree);

        String steered = runner.steerAgent(sessionId, "please hurry");
        assertTrue(steered.contains("注入"), steered);

        String terminated = runner.terminateAgent(sessionId);
        assertTrue(terminated.contains("已终止"), terminated);
        assertTrue(runner.activeRuns().isEmpty());
    }

    @Test
    void resolveEffectiveToolsStripsRuntimeMgmt() {
        CustomSubAgentDefinition def = new CustomSubAgentDefinition(
                "x", "d", "b", null, null, null,
                List.of("read_file", "terminate_agent", "steer_agent", "running_agents_list"),
                List.of(), List.of(), "",
                "", "",
                CustomSubAgentDefinition.Source.USER, null, null);
        assertEquals(java.util.Set.of("read_file"),
                def.resolveEffectiveTools(java.util.Set.of(
                        "read_file", "terminate_agent", "steer_agent", "running_agents_list")));
    }
}
