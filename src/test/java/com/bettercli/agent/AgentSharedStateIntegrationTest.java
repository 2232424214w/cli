package com.bettercli.agent;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import com.bettercli.memory.LongTermMemory;
import com.bettercli.memory.MemoryManager;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AgentOrchestrator 集成 SharedState 黑板：run() 后黑板被正确填充
 * （goal / plan / artifacts / reviews / routingLog），对标 2026 Blackboard + routing 审计。
 */
class AgentSharedStateIntegrationTest {

    @Test
    void runPopulatesSharedStateBlackboard(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "执行任务",
                              "type": "COMMAND",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("执行结果内容"),
                response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """)
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        orchestrator.run("测试黑板填充");

        SharedState state = orchestrator.getSharedState();
        assertNotNull(state, "run() 后应创建 SharedState");
        assertEquals("测试黑板填充", state.getGoal());
        assertNotNull(state.getPlan(), "planner 产物应写入黑板 plan");
        assertTrue(state.getPlan().contains("单步任务"), "plan 应包含 planner 输出");

        // worker 产物应写入黑板（parsePlan 会把 step id 重编号为 step_1）
        assertEquals("执行结果内容", state.getArtifact("step_1"));

        // reviewer 反馈应写入黑板
        assertNotNull(state.getReview("step_1"), "reviewer 反馈应写入黑板");
        assertTrue(state.getReview("step_1").contains("approved"));

        // routing 决策应入审计日志（至少 1 条：step_1 的派活）
        assertTrue(state.getRoutingLog().size() >= 1, "routing 决策应被记录");
        assertEquals("step_1", state.getRoutingLog().get(0).stepId());
        assertNotNull(state.getRoutingLog().get(0).assignee());
        assertTrue(state.getRoutingLog().get(0).timestamp() > 0);
    }

    @Test
    void runWithRetryUpdatesArtifactToLatestExecution(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步",
                          "steps": [{"id":"s1","description":"执行","type":"COMMAND","dependencies":[]}]
                        }
                        """),
                response("第一次结果"),
                response("""
                        {"approved": false, "summary": "未通过", "issues": ["改"]}
                        """),
                response("第二次结果"),
                response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """)
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, new ToolRegistry(), new NoOpMemoryManager(tempDir.toFile()));
        orchestrator.run("测试重试黑板覆盖");

        // 重试后黑板 artifact 应是最新一次有效执行（第二次结果，step id 重编号为 step_1）
        assertEquals("第二次结果", orchestrator.getSharedState().getArtifact("step_1"));
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final java.util.Queue<LlmClient.ChatResponse> responses;

        private StubGLMClient(List<LlmClient.ChatResponse> responses) {
            super("test-key");
            this.responses = new java.util.ArrayDeque<>(responses);
        }

        @Override
        public LlmClient.ChatResponse chat(List<LlmClient.Message> messages, List<Tool> tools) throws java.io.IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public LlmClient.ChatResponse chat(List<LlmClient.Message> messages, List<Tool> tools,
                                            LlmClient.StreamListener listener) throws java.io.IOException {
            LlmClient.ChatResponse r = responses.poll();
            if (r == null) throw new java.io.IOException("缺少预设响应");
            if (r.content() != null && !r.content().isEmpty()) {
                listener.onContentDelta(r.content());
            }
            return r;
        }
    }
}
