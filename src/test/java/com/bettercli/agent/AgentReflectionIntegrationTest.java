package com.bettercli.agent;

import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ReAct 循环中工具失败后反思提示被注入 conversationHistory，
 * 供下一轮 LLM 引用；以及反螺旋在连续失败超阈值后停止注入。
 */
class AgentReflectionIntegrationTest {

    private static LlmClient.ToolCall readFile(String path) {
        return new LlmClient.ToolCall("call_1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"" + path + "\"}"));
    }

    /** 统计注入的反思提示总数。conversationHistory 累积，最后一轮 LLM 请求历史包含所有已注入的反思提示。 */
    private static long countReflectionPrompts(List<List<LlmClient.Message>> messagesByCall) {
        if (messagesByCall.isEmpty()) return 0;
        List<LlmClient.Message> lastCall = messagesByCall.get(messagesByCall.size() - 1);
        return lastCall.stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null && m.content().contains("[反思提示]"))
                .count();
    }

    @Test
    void injectsReflectionPromptAfterToolFailure(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(readFile("missing.txt")), 10, 2),
                new LlmClient.ChatResponse("assistant", "我已注意到读取失败并改换策略。", null, 20, 5)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("读取 missing.txt");

        assertEquals(2, llm.messagesByCall.size(), "应有两轮 LLM 调用");
        // 第二轮请求历史应包含第一轮失败后注入的反思提示
        List<LlmClient.Message> secondCall = llm.messagesByCall.get(1);
        assertTrue(secondCall.stream()
                        .anyMatch(m -> "user".equals(m.role()) && m.content() != null
                                && m.content().contains("[反思提示]") && m.content().contains("read_file")),
                "第二轮请求历史应包含 read_file 失败的反思提示");
        assertEquals(1, countReflectionPrompts(llm.messagesByCall));
    }

    @Test
    void antiSpiralStopsInjectionAfterMaxConsecutive(@TempDir Path tempDir) {
        // 用不同文件名避免 AgentBudget stagnation（连续相同签名）兜底打断，
        // 专门验证 ReflectionService 自身反螺旋：默认 maxConsecutive=2
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(readFile("a.txt")), 10, 2),
                new LlmClient.ChatResponse("assistant", "", List.of(readFile("b.txt")), 10, 2),
                new LlmClient.ChatResponse("assistant", "", List.of(readFile("c.txt")), 10, 2),
                new LlmClient.ChatResponse("assistant", "结束", null, 10, 2)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("连续读取不存在的文件");

        assertEquals(4, llm.messagesByCall.size(), "应有四轮 LLM 调用");
        // 第1、2轮失败注入反思（计数1、2），第3轮失败超阈值不注入
        assertEquals(2, countReflectionPrompts(llm.messagesByCall),
                "反思提示应只注入 2 次（maxConsecutive=2），第3次失败不再注入");
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() { return "test"; }

        @Override
        public String getProviderName() { return "test"; }

        @Override
        public int maxContextWindow() { return 256_000; }
    }
}
