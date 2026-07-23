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
 * 验证 update_plan 工具在 ReAct loop 中被调用后，Agent 的 PlanStore 被正确更新，
 * 且工具结果回灌到 conversationHistory 供下一轮 LLM 引用。
 */
class AgentUpdatePlanIntegrationTest {

    @Test
    void updatePlanToolUpdatesAgentPlanStore(@TempDir Path tempDir) {
        LlmClient.ToolCall updatePlan = new LlmClient.ToolCall(
                "call_1",
                new LlmClient.ToolCall.Function(
                        "update_plan",
                        "{\"tasks\":\"[ ] 读取 auth 模块\\n[~] 重构 token 校验\\n[x] 补测试\"}"
                )
        );
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(updatePlan), 10, 2),
                new LlmClient.ChatResponse("assistant", "计划已就绪，开始执行。", null, 20, 5)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("帮我重构 auth 模块");

        PlanStore store = agent.getPlanStore();
        assertEquals(3, store.size(), "planStore 应被 update_plan 工具更新为 3 条");
        assertEquals(1, store.completedCount(), "应有 1 条已完成");
        assertEquals(ReActPlan.Status.PENDING, store.snapshot().get(0).status());
        assertEquals(ReActPlan.Status.IN_PROGRESS, store.snapshot().get(1).status());
        assertEquals(ReActPlan.Status.COMPLETED, store.snapshot().get(2).status());

        // 工具结果应回灌到第二轮 LLM 请求的历史
        assertEquals(2, llm.messagesByCall.size());
        List<LlmClient.Message> secondCall = llm.messagesByCall.get(1);
        assertTrue(secondCall.stream()
                        .filter(m -> "tool".equals(m.role()))
                        .anyMatch(m -> m.content().contains("计划已更新") && m.content().contains("1/3")),
                "第二轮请求历史应包含 update_plan 的工具结果");
    }

    @Test
    void clearHistoryAlsoClearsPlanStore(@TempDir Path tempDir) {
        LlmClient.ToolCall updatePlan = new LlmClient.ToolCall(
                "call_1",
                new LlmClient.ToolCall.Function("update_plan", "{\"tasks\":\"[ ] 任务一\\n[ ] 任务二\"}")
        );
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(updatePlan), 10, 2),
                new LlmClient.ChatResponse("assistant", "ok", null, 20, 5)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, registry);

        agent.run("列个计划");
        assertEquals(2, agent.getPlanStore().size());

        agent.clearHistory();
        assertEquals(0, agent.getPlanStore().size(), "/clear 应同时清空 planStore");
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
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }
    }
}
