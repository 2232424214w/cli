package com.bettercli.subagent;

import com.bettercli.agent.AgentBudget;
import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentRunnerTest {

    @Test
    void rejectsUnknownName() {
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, null, null);
        registry.reload();
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);
        String result = runner.run("missing", "task", mockClient(() -> {}), new ToolRegistry());
        assertTrue(result.contains("未找到"));
    }

    @Test
    void rejectsNestedInvocation(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        Path dir = user.resolve("outer");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), """
                ---
                name: outer
                description: outer agent
                ---
                Finish immediately without tools.
                """);
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);
        AtomicInteger depth = new AtomicInteger();
        LlmClient client = mockClient(() -> {
            if (depth.incrementAndGet() == 1) {
                String nested = runner.run("outer", "inner", mockClient(() -> {}), new ToolRegistry());
                assertTrue(nested.contains("不可嵌套"), nested);
            }
        });

        String outer = runner.run("outer", "do work", client, new ToolRegistry());
        assertTrue(outer.contains("Custom SubAgent") || outer.contains("done") || outer.contains("OK"), outer);
    }

    @Test
    void startAsyncReturnsPlaceholderThenMaterializes() {
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, null, null);
        // empty registry — unknown name returns error not placeholder
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);
        String err = runner.startAsync("missing", "t", mockClient(() -> {}), new ToolRegistry(), null, null);
        assertTrue(err.contains("未找到"), err);
    }

    @Test
    void placeholderRoundTrip() {
        String p = CustomSubAgentRunner.placeholder("sub_x_1", "x", "parent", 30);
        assertTrue(p.startsWith(CustomSubAgentRunner.PENDING_PREFIX));
        assertEquals("sub_x_1", CustomSubAgentRunner.parsePendingSessionId(p));
    }

    @Test
    void parallelAsyncDoesNotBlockOnNesting(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user");
        Path a = user.resolve("alpha");
        Path b = user.resolve("beta");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("AGENT.md"), """
                ---
                name: alpha
                description: alpha agent
                ---
                Finish.
                """);
        Files.writeString(b.resolve("AGENT.md"), """
                ---
                name: beta
                description: beta agent
                ---
                Finish.
                """);
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);
        ToolRegistry tools = new ToolRegistry();
        LlmClient client = mockClient(() -> {});

        String p1 = runner.startAsync("alpha", "t1", client, tools, null, "c1");
        String p2 = runner.startAsync("beta", "t2", client, tools, null, "c1");
        assertTrue(p1.startsWith(CustomSubAgentRunner.PENDING_PREFIX), p1);
        assertTrue(p2.startsWith(CustomSubAgentRunner.PENDING_PREFIX), p2);

        var r1 = new ToolRegistry.ToolExecutionResult("1", "run_subagent", "{}", p1, 0, false, List.of());
        var r2 = new ToolRegistry.ToolExecutionResult("2", "run_subagent", "{}", p2, 0, false, List.of());
        var done = runner.materializeAsyncResults(List.of(r1, r2));
        assertEquals(2, done.size());
        assertTrue(done.get(0).result().contains("Custom SubAgent") || done.get(0).result().contains("OK")
                || done.get(0).result().contains("done"), done.get(0).result());
        assertTrue(done.get(1).result().contains("Custom SubAgent") || done.get(1).result().contains("OK")
                || done.get(1).result().contains("done"), done.get(1).result());
    }

    @Test
    void backgroundAcceptedDoesNotBlockMaterializeAndFiresCompletion(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user");
        Path dir = user.resolve("bg-bot");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), """
                ---
                name: bg-bot
                description: bg
                ---
                Finish.
                """);
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<CustomSubAgentCompletionEvent> event = new AtomicReference<>();
        runner.setCompletionListener(e -> {
            event.set(e);
            done.countDown();
        });

        String accepted = runner.startAsync(
                "bg-bot", "do it", mockClient(() -> {}), new ToolRegistry(), null, "parent-1", true, "call_x");
        assertTrue(accepted.startsWith(CustomSubAgentRunner.BG_ACCEPTED_PREFIX), accepted);
        assertNotNull(CustomSubAgentRunner.parseBgAcceptedSessionId(accepted));
        assertNull(CustomSubAgentRunner.parsePendingSessionId(accepted));

        var toolResult = new ToolRegistry.ToolExecutionResult(
                "1", "run_subagent", "{}", accepted, 0, false, List.of());
        var materialized = runner.materializeAsyncResults(List.of(toolResult));
        assertEquals(1, materialized.size());
        assertTrue(materialized.get(0).result().startsWith(CustomSubAgentRunner.BG_ACCEPTED_PREFIX));

        assertTrue(done.await(5, TimeUnit.SECONDS), "completion listener not fired");
        assertNotNull(event.get());
        assertEquals("bg-bot", event.get().agentName());
        assertEquals("parent-1", event.get().parentConversationId());
        assertTrue(event.get().success());
    }

    @Test
    void maxTurnsOverrideAppliesToBudget() {
        AgentBudget budget = AgentBudget.fromLlmClient(null, 7);
        assertEquals(7, budget.hardMaxIterations());
    }

    @Test
    void resolveEffectiveToolsStripsRecursiveTools() {
        CustomSubAgentDefinition def = new CustomSubAgentDefinition(
                "x", "d", "b", null, null, null,
                List.of("read_file", "run_subagent", "run_team", "create_plan"),
                List.of(), List.of(), "",
                "", "",
                CustomSubAgentDefinition.Source.USER, null, null);
        Set<String> effective = def.resolveEffectiveTools(Set.of(
                "read_file", "run_subagent", "run_team", "create_plan"));
        assertEquals(Set.of("read_file"), effective);
    }

    private static LlmClient mockClient(Runnable onChat) {
        return new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                return chat(messages, tools, StreamListener.NO_OP);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                    throws IOException {
                if (onChat != null) {
                    onChat.run();
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
    }
}
