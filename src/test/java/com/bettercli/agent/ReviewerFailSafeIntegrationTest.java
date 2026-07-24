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
 * reviewer LLM 失败：fail-safe 重试后仍失败则不放行；瞬时失败重试成功则通过。
 */
class ReviewerFailSafeIntegrationTest {

    @Test
    void reviewerTransientFailureThenSucceeds(@TempDir Path tempDir) {
        AtomicInteger reviewCalls = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "单步",
                          "steps": [
                            {"id": "s1", "description": "分析任务ALPHA", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                int n = reviewCalls.incrementAndGet();
                if (n == 1) {
                    return null; // 触发 IOException → AgentMessage.ERROR → fail-safe 重试
                }
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            if (body.contains("当前任务：分析任务ALPHA") || body.trim().equals("分析任务ALPHA")) {
                return response("分析完成结果");
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String result = orchestrator.run("测试审查瞬时失败后恢复");
        assertTrue(reviewCalls.get() >= 2, "应至少 fail-safe 重试一次，实际=" + reviewCalls.get());
        assertTrue(result.contains("分析完成结果"), "重试成功后应保留 worker 结果");
        assertFalse(result.contains("审查服务不可用"), "不应以审查失败收尾");
    }

    @Test
    void reviewerPersistentFailureDoesNotPassThrough(@TempDir Path tempDir) {
        AtomicInteger reviewCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划") && !body.contains("需要重新规划")) {
                return response("""
                        {
                          "summary": "单步",
                          "steps": [
                            {"id": "s1", "description": "分析任务BETA", "type": "ANALYSIS", "dependencies": []}
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
                            {"id": "alt", "description": "替代步骤DELTA", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                reviewCalls.incrementAndGet();
                return null; // 持续失败
            }
            if (body.contains("当前任务：替代步骤DELTA") || body.trim().equals("替代步骤DELTA")) {
                return response("替代完成");
            }
            if (body.contains("当前任务：分析任务BETA") || body.trim().equals("分析任务BETA")) {
                return response("本应被审查挡住的结果");
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String result = orchestrator.run("测试审查持续失败不放行");
        assertTrue(reviewCalls.get() >= 2, "fail-safe 应重试，实际=" + reviewCalls.get());
        // 不放行后应走 FAILED → 可能 replan；无论如何原始未审查结果不应被当成成功步骤保留
        assertTrue(replanCalls.get() >= 1 || result.contains("审查服务不可用") || result.contains("失败"),
                "持续审查失败应 fail-safe 不放行或触发重规划，result=" + result);
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
                throw new IOException("模拟审查 LLM 失败: " + lastUser);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }
}
