package com.bettercli.agent;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import com.bettercli.memory.LongTermMemory;
import com.bettercli.memory.MemoryManager;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 AgentOrchestrator 动态重规划：step 失败 / 审查耗尽时触发 planner replan，
 * 保留已完成步骤，用新计划替换剩余 PENDING。
 */
class ReplanIntegrationTest {

    @Test
    void shouldReplanWhenWorkerFailsAndSkipCompletedSteps(@TempDir Path tempDir) {
        // 场景：step_1 成功 → step_2 Worker 失败 → 触发 replan → 新计划只含替代步骤 → 通过
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                planCalls.incrementAndGet();
                return response("""
                        {
                          "summary": "两步任务",
                          "steps": [
                            {"id": "s1", "description": "成功步骤ALPHA", "type": "ANALYSIS", "dependencies": []},
                            {"id": "s2", "description": "必失败步骤BETA", "type": "COMMAND", "dependencies": ["s1"]}
                          ]
                        }
                        """);
            }
            if (body.contains("需要重新规划剩余步骤")) {
                replanCalls.incrementAndGet();
                return response("""
                        {
                          "summary": "重规划后",
                          "steps": [
                            {"id": "alt", "description": "替代步骤GAMMA", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            // 按「当前任务」匹配，避免依赖上下文中的已完成步骤描述误命中
            if (body.contains("当前任务：必失败步骤BETA") || body.trim().equals("必失败步骤BETA")) {
                return response("");
            }
            if (body.contains("当前任务：替代步骤GAMMA") || body.trim().equals("替代步骤GAMMA")) {
                return response("替代步骤完成结果");
            }
            if (body.contains("当前任务：成功步骤ALPHA") || body.trim().equals("成功步骤ALPHA")) {
                return response("第一步完成结果");
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String result = orchestrator.run("测试 worker 失败后动态调整计划");

        assertEquals(1, planCalls.get(), "初始规划应只调用一次");
        assertEquals(1, replanCalls.get(), "Worker 失败应触发恰好一次 replan，实际=" + replanCalls.get());
        assertTrue(result.contains("成功步骤ALPHA"), "应保留已完成步骤");
        assertTrue(result.contains("替代步骤GAMMA"), "应包含重规划后的替代步骤");
        assertTrue(result.contains("第一步完成结果"), "已完成步骤产物应保留在总结中");
        SharedState state = orchestrator.getSharedState();
        assertNotNull(state);
        assertNotNull(state.getArtifact("step_1"), "已完成步骤产物应在黑板");
        assertEquals("第一步完成结果", state.getArtifact("step_1"));
        assertNotNull(state.getArtifact("r1_step_1"), "重规划步骤产物应在黑板");
    }

    @Test
    void shouldReplanWhenReviewExhausted(@TempDir Path tempDir) {
        // 场景：每轮审查拒绝且 issues 不同（避免收敛）→ 耗尽 MAX_RETRIES → EXHAUSTED → replan
        AtomicInteger replanCalls = new AtomicInteger();
        AtomicInteger reviewRejects = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "单步",
                          "steps": [
                            {"id": "s1", "description": "审查会拒绝的步骤", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("需要重新规划剩余步骤")) {
                replanCalls.incrementAndGet();
                return response("""
                        {
                          "summary": "重规划",
                          "steps": [
                            {"id": "fixed", "description": "修好的步骤", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("审查会拒绝的步骤") && !body.contains("执行者自报摘要")) {
                return response("有缺陷的执行结果");
            }
            if (body.contains("修好的步骤") && !body.contains("执行者自报摘要")) {
                return response("修好后的结果");
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                if (body.contains("修好")) {
                    return response("""
                            {"approved": true, "summary": "通过", "issues": []}
                            """);
                }
                // 每轮不同 issues，避免 isDebateConverged 提前收敛
                int n = reviewRejects.incrementAndGet();
                return response("""
                        {"approved": false, "summary": "未通过", "issues": ["问题轮次%d"]}
                        """.formatted(n));
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String result = orchestrator.run("测试审查耗尽触发重规划");

        assertTrue(replanCalls.get() >= 1, "审查耗尽应触发至少一次 replan，实际=" + replanCalls.get());
        assertTrue(reviewRejects.get() >= 2, "应至少拒绝初始审查 + 重试，实际=" + reviewRejects.get());
        assertTrue(result.contains("修好") || result.contains("修好后的结果") || result.contains("✅"),
                "重规划后应完成任务，result=" + result);
    }

    @Test
    void shouldCapReplanAtMaxPerRun(@TempDir Path tempDir) {
        // 场景：每次 replan 后的新步骤仍失败 → 最多 MAX_REPLAN_PER_RUN=2 次 replan
        AtomicInteger replanCalls = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "会反复失败",
                          "steps": [
                            {"id": "s1", "description": "永远失败", "type": "COMMAND", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("需要重新规划剩余步骤")) {
                int n = replanCalls.incrementAndGet();
                return response("""
                        {
                          "summary": "重规划#%d",
                          "steps": [
                            {"id": "r", "description": "永远失败", "type": "COMMAND", "dependencies": []}
                          ]
                        }
                        """.formatted(n));
            }
            // Worker：空结果 → FAILED
            if (body.contains("永远失败") && !body.contains("执行者自报摘要")
                    && !body.contains("需要重新规划剩余步骤")) {
                return response("");
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        orchestrator.run("测试 replan 上限");

        assertEquals(2, replanCalls.get(),
                "replan 应被 MAX_REPLAN_PER_RUN=2 截断，实际=" + replanCalls.get());
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }
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
            if (response == null) {
                throw new IOException("无匹配响应: " + lastUser);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }
}
