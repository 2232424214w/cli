package com.bettercli.eval;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eval 最小闭环：RecordingClient 脚本化写文件 → DeterministicScorer → EvalReport。
 * 不调用真实 LLM，验证 harness 本身可测、可出对比表。
 */
class EvalRunnerTest {

    @Test
    void runsGoldenTasksWithScriptedLlm(@TempDir Path tempDir) throws Exception {
        Path tasksFile = Path.of("evals", "golden-tasks.jsonl");
        List<GoldenTask> tasks = GoldenTask.loadJsonl(tasksFile);
        assertTrue(tasks.size() >= 5, "golden set should have at least 5 tasks");

        EvalRunner runner = new EvalRunner(task -> scriptedWriter(task));
        EvalReport report = runner.runAll(tasks, tempDir.resolve("workspaces"));

        assertEquals(tasks.size(), report.total());
        assertEquals(tasks.size(), report.passed(), () -> report.toMarkdown());
        assertTrue(report.successRate() >= 0.999);
        assertTrue(report.toMarkdown().contains("success_rate"));
        assertTrue(report.toMarkdown().contains("| id |"));
    }

    @Test
    void failsWhenAgentDoesNotSatisfyCriteria(@TempDir Path tempDir) {
        GoldenTask task = new GoldenTask(
                "expect-fail",
                "react",
                "easy-fail",
                "do nothing useful",
                List.of(new SuccessCriterion("file_exists", "never.txt", ""))
        );
        EvalRunner runner = new EvalRunner(t -> new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "I did nothing", null, 5, 2)
        )));
        TaskRunResult result = runner.runOne(task, tempDir.resolve(task.id()));
        assertEquals(false, result.success());
    }

    /** 按任务 id 发出对应 write_file 工具调用，最后一轮返回纯文本收尾。 */
    private static LlmClient scriptedWriter(GoldenTask task) {
        return switch (task.id()) {
            case "write-hello" -> singleWrite("hello.txt", "hello world");
            case "write-readme" -> singleWrite("README.md", "BetterCLI Eval");
            case "multi-file" -> multiWrite(
                    writeCall("c1", "src/App.java", "public class App {}"),
                    writeCall("c2", "notes.txt", "done")
            );
            case "list-then-write" -> new ScriptedClient(List.of(
                    new LlmClient.ChatResponse("assistant", "",
                            List.of(new LlmClient.ToolCall("c0",
                                    new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"))),
                            10, 2),
                    new LlmClient.ChatResponse("assistant", "",
                            List.of(writeCall("c1", "found.txt", "ok")), 10, 2),
                    new LlmClient.ChatResponse("assistant", "done", null, 5, 1)
            ));
            case "overwrite-content" -> singleWrite("config.json", "{\"mode\":\"eval\"}");
            case "nested-path" -> singleWrite("docs/guide.md", "Getting Started");
            case "two-notes" -> multiWrite(
                    writeCall("c1", "a.txt", "A"),
                    writeCall("c2", "b.txt", "B")
            );
            case "echo-marker" -> singleWrite("marker.txt", "EVAL_OK");
            default -> new ScriptedClient(List.of(
                    new LlmClient.ChatResponse("assistant", "unsupported task in script", null, 1, 1)
            ));
        };
    }

    private static ScriptedClient singleWrite(String path, String content) {
        return new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(writeCall("c1", path, content)), 10, 2),
                new LlmClient.ChatResponse("assistant", "done", null, 5, 1)
        ));
    }

    private static ScriptedClient multiWrite(LlmClient.ToolCall... calls) {
        return new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(calls), 20, 4),
                new LlmClient.ChatResponse("assistant", "done", null, 5, 1)
        ));
    }

    private static LlmClient.ToolCall writeCall(String id, String path, String content) {
        String json = "{\"path\":\"" + path + "\",\"content\":"
                + quoteJson(content) + "}";
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function("write_file", json));
    }

    private static String quoteJson(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses;

        private ScriptedClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "eval-script";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 128_000;
        }
    }
}
