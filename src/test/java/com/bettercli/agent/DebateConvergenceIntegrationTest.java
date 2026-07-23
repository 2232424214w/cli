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
 * 验证 Multi-Agent 增量辩论收敛：审查连续给出相同 issues 时停止重试，不触发 EXHAUSTED/replan。
 */
class DebateConvergenceIntegrationTest {

    @Test
    void shouldStopRetryWhenReviewIssuesConverge(@TempDir Path tempDir) {
        AtomicInteger workerAttempts = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "单步",
                          "steps": [
                            {"id": "s1", "description": "会收敛的步骤", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("需要重新规划剩余步骤")) {
                replanCalls.incrementAndGet();
                return response("""
                        {"summary": "不应到这里", "steps": [
                          {"id": "x", "description": "x", "type": "ANALYSIS", "dependencies": []}
                        ]}
                        """);
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                // 始终拒绝，且 issues 相同 → 应触发收敛
                return response("""
                        {"approved": false, "summary": "未通过", "issues": ["缺错误处理"]}
                        """);
            }
            if (body.contains("会收敛的步骤") || body.contains("增量辩论")) {
                workerAttempts.incrementAndGet();
                return response("带缺陷的结果 v" + workerAttempts.get());
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String result = orchestrator.run("测试辩论收敛");

        assertEquals(0, replanCalls.get(), "收敛不应触发 replan");
        // 初始执行 1 次 + 至多 MAX_RETRIES 次；若收敛生效，应少于「初始+满重试」的最坏情况
        // 收敛发生在进入第 2 次重试前（previousIssues 已有值且相同）→ worker 约 2 次（初始+1）
        assertTrue(workerAttempts.get() >= 2 && workerAttempts.get() <= 3,
                "收敛应提前停止辩论，workerAttempts=" + workerAttempts.get());
        assertTrue(result.contains("会收敛") || result.contains("✅") || result.contains("⚠️"),
                "应有执行总结");
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
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }
}
