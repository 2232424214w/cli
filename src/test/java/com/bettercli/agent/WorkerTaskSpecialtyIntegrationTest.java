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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class WorkerTaskSpecialtyIntegrationTest {

    @Test
    void injectsTypeSpecialtyIntoWorkerContext(@TempDir Path tempDir) {
        AtomicBoolean sawAnalysisSpecialty = new AtomicBoolean();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "单步",
                          "steps": [
                            {"id": "s1", "description": "分析代码结构", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("审查要求") || (body.contains("原始任务") && body.contains("执行者自报摘要"))) {
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            if (body.contains("当前任务：分析代码结构") || body.contains("分析代码结构")) {
                if (body.contains("任务类型专精指引") && body.contains("ANALYSIS")) {
                    sawAnalysisSpecialty.set(true);
                }
                return response("分析完成");
            }
            return response("fallback");
        };

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                new DispatchingStub(dispatcher),
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );
        orchestrator.run("测试类型专精注入");

        assertTrue(sawAnalysisSpecialty.get(), "worker context 应包含 ANALYSIS 专精指引");
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
