package com.bettercli.agent;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 WorkflowAdapters 胶水：Workflow 节点真正调 LLM，产物入黑板；fan-in 汇总多路 artifact。
 */
class WorkflowLlmNodeTest {

    @Test
    void subAgentActionInvokesLlmAndStoresArtifactViaRuntime(@TempDir Path tempDir) {
        AtomicInteger chatCalls = new AtomicInteger();
        LlmClient stub = new DispatchingStub(body -> {
            chatCalls.incrementAndGet();
            return response("LLM产出:" + body.replace("\n", " ").trim());
        });
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        SubAgent worker = new SubAgent("w1", AgentRole.WORKER, stub, tools);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        WorkflowScript script = new WorkflowScript("LLM节点测试", List.of(
                WorkflowAdapters.llmTask("s1", "分析模块X", worker, out)
        ));
        SharedState state = new SharedState();
        state.setGoal("理解模块X", null);

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertTrue(result.completed(), result.summary());
        assertTrue(chatCalls.get() >= 1, "应至少调用一次 LLM");
        assertNotNull(state.getArtifact("s1"));
        assertTrue(state.getArtifact("s1").contains("LLM产出")
                        || state.getArtifact("s1").contains("分析模块X"),
                "产物应来自 LLM，实际=" + state.getArtifact("s1"));
        assertEquals(List.of("s1"), result.executedStepIds());
    }

    @Test
    void fanInActionReadsMultipleArtifactsAndSynthesizes(@TempDir Path tempDir) {
        AtomicInteger chatCalls = new AtomicInteger();
        LlmClient stub = new DispatchingStub(body -> {
            chatCalls.incrementAndGet();
            // fan-in prompt 应包含各路产物
            if (body.contains("合成目标") || body.contains("各路调研")) {
                assertTrue(body.contains("角度A结果"), "fan-in prompt 应含 artifact a");
                assertTrue(body.contains("角度B结果"), "fan-in prompt 应含 artifact b");
                return response("综合结论：A+B");
            }
            return response("unexpected:" + body);
        });
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        SubAgent synthesizer = new SubAgent("synth", AgentRole.WORKER, stub, tools);

        // 先用纯函数 fan-out 写入两路产物，再 fan-in
        WorkflowScript script = new WorkflowScript("scatter-gather", List.of(
                new ParallelStep("fanout", List.of(
                        new TaskStep("a", "角度A", st -> "角度A结果"),
                        new TaskStep("b", "角度B", st -> "角度B结果")
                )),
                WorkflowAdapters.fanInTask("gather", "综合两路调研", synthesizer,
                        List.of("a", "b"), new PrintStream(PrintStream.nullOutputStream()))
        ));
        SharedState state = new SharedState();
        state.setGoal("调研某问题", null);

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertTrue(result.completed(), result.summary());
        assertEquals(1, chatCalls.get(), "fan-in 应恰好一次 LLM 调用");
        assertEquals("角度A结果", state.getArtifact("a"));
        assertEquals("角度B结果", state.getArtifact("b"));
        assertEquals("综合结论：A+B", state.getArtifact("gather"));
        assertTrue(result.executedStepIds().contains("gather"));
    }

    @Test
    void subAgentActionPropagatesLlmErrorAsRuntimeAbort(@TempDir Path tempDir) {
        LlmClient failing = new GLMClient("test-key") {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                throw new IOException("模拟 LLM 宕机");
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                    throws IOException {
                throw new IOException("模拟 LLM 宕机");
            }
        };
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        SubAgent worker = new SubAgent("w1", AgentRole.WORKER, failing, tools);

        WorkflowScript script = new WorkflowScript("错误传播", List.of(
                WorkflowAdapters.llmTask("s1", "会失败的任务", worker,
                        new PrintStream(PrintStream.nullOutputStream())),
                new TaskStep("s2", "不应执行", st -> "NOPE")
        ));
        SharedState state = new SharedState();

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertFalse(result.completed());
        assertTrue(result.summary().contains("LLM") || result.summary().contains("失败")
                        || result.summary().contains("宕机"),
                "摘要应反映 LLM 失败，实际=" + result.summary());
        assertNull(state.getArtifact("s2"), "失败后不应继续执行后续步骤");
    }

    @Test
    void fanInActionRejectsEmptyArtifactKeys() {
        SubAgent dummy = new SubAgent("d", AgentRole.WORKER, new GLMClient("k"), new ToolRegistry());
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowAdapters.fanInAction(dummy, List.of(), "goal", null));
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 10, 5);
    }

    private static final class DispatchingStub extends GLMClient {
        private final Function<String, ChatResponse> dispatcher;

        private DispatchingStub(Function<String, ChatResponse> dispatcher) {
            super("test-key");
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            String lastUser = "";
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).role())) {
                    lastUser = messages.get(i).content() == null ? "" : messages.get(i).content();
                    break;
                }
            }
            ChatResponse response = dispatcher.apply(lastUser);
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }
}
