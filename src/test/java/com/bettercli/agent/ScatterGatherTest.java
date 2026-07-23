package com.bettercli.agent;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ScatterGather：同一目标多角度并行调研 + fan-in 合成。
 */
class ScatterGatherTest {

    @Test
    void exploreRunsFanOutThenFanIn(@TempDir Path tempDir) {
        AtomicInteger angleCalls = new AtomicInteger();
        AtomicInteger gatherCalls = new AtomicInteger();

        LlmClient stub = new DispatchingStub(body -> {
            if (body.contains("合成目标") || body.contains("各路调研")) {
                gatherCalls.incrementAndGet();
                assertTrue(body.contains("安全") || body.contains("angle"),
                        "fan-in 应收到各路产物");
                return response("最终综合结论");
            }
            if (body.contains("调研子角度")) {
                angleCalls.incrementAndGet();
                if (body.contains("安全")) return response("安全角度结论");
                if (body.contains("性能")) return response("性能角度结论");
                return response("其他角度结论");
            }
            return response("fallback");
        });

        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        SubAgent w1 = new SubAgent("w1", AgentRole.WORKER, stub, tools);
        SubAgent w2 = new SubAgent("w2", AgentRole.WORKER, stub, tools);
        SubAgent synth = new SubAgent("synth", AgentRole.WORKER, stub, tools);

        SharedState state = new SharedState();
        WorkflowScript.WorkflowResult result = ScatterGather.explore(
                "评估认证模块",
                List.of("安全", "性能"),
                List.of(w1, w2),
                synth,
                state,
                new PrintStream(PrintStream.nullOutputStream())
        );

        assertTrue(result.completed(), result.summary());
        assertEquals(2, angleCalls.get(), "两路角度应各调一次 LLM");
        assertEquals(1, gatherCalls.get(), "fan-in 应恰好一次合成");
        assertEquals("安全角度结论", state.getArtifact("angle_1"));
        assertEquals("性能角度结论", state.getArtifact("angle_2"));
        assertEquals("最终综合结论", state.getArtifact("gather"));
        assertTrue(result.executedStepIds().contains("gather"));
    }

    @Test
    void buildRejectsLessThanTwoAngles() {
        SubAgent w = new SubAgent("w", AgentRole.WORKER, new GLMClient("k"), new ToolRegistry());
        assertThrows(IllegalArgumentException.class,
                () -> ScatterGather.build("g", List.of("only"), List.of(w), w, null));
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
