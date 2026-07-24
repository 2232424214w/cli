package com.bettercli.eval;

import com.bettercli.agent.Agent;
import com.bettercli.llm.LlmClient;
import com.bettercli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Agent 任务级 Eval Runner（最小闭环）。
 *
 * <p>每条任务在独立临时工作目录跑 ReAct Agent，用确定性判分收口。
 * LLM 由调用方注入（真实 client 或 RecordingClient），不绑死 provider。
 * plan/team 模式字段已预留，最小闭环只实现 react。
 */
public final class EvalRunner {

    @FunctionalInterface
    public interface LlmFactory {
        LlmClient create(GoldenTask task) throws IOException;
    }

    private final LlmFactory llmFactory;
    private final Function<Path, ToolRegistry> registryFactory;

    public EvalRunner(LlmFactory llmFactory) {
        this(llmFactory, workspace -> {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(workspace.toString());
            return registry;
        });
    }

    public EvalRunner(LlmFactory llmFactory, Function<Path, ToolRegistry> registryFactory) {
        this.llmFactory = Objects.requireNonNull(llmFactory, "llmFactory");
        this.registryFactory = Objects.requireNonNull(registryFactory, "registryFactory");
    }

    public EvalReport runAll(List<GoldenTask> tasks, Path workRoot) throws IOException {
        Files.createDirectories(workRoot);
        List<TaskRunResult> results = new ArrayList<>();
        for (GoldenTask task : tasks) {
            results.add(runOne(task, workRoot.resolve(task.id())));
        }
        return EvalReport.from(results);
    }

    public TaskRunResult runOne(GoldenTask task, Path workspace) {
        long start = System.nanoTime();
        try {
            if (!"react".equals(task.mode())) {
                return fail(task, start, "mode not supported in minimal harness: " + task.mode());
            }
            Files.createDirectories(workspace);
            CountingLlmClient llm = new CountingLlmClient(llmFactory.create(task));
            ToolRegistry registry = registryFactory.apply(workspace);
            Agent agent = new Agent(llm, registry);
            String output = agent.run(task.input());
            DeterministicScorer.ScoreOutcome score = DeterministicScorer.score(workspace, task.success());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return new TaskRunResult(
                    task.id(),
                    task.mode(),
                    score.success(),
                    score.details(),
                    output,
                    llm.callCount(),
                    llm.callCount(),
                    llm.inputTokens(),
                    llm.outputTokens(),
                    elapsedMs,
                    ""
            );
        } catch (Exception e) {
            return fail(task, start, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static TaskRunResult fail(GoldenTask task, long startNanos, String error) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return new TaskRunResult(
                task.id(), task.mode(), false, List.of("FAIL runner: " + error),
                "", 0, 0, 0, 0, elapsedMs, error
        );
    }

    /** 轻量计数包装，避免依赖测试包 CountingLlmClient。 */
    static final class CountingLlmClient implements LlmClient {
        private final LlmClient delegate;
        private int calls;
        private int inputTokens;
        private int outputTokens;

        CountingLlmClient(LlmClient delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls++;
            ChatResponse response = delegate.chat(messages, tools, listener);
            if (response != null) {
                inputTokens += Math.max(0, response.inputTokens());
                outputTokens += Math.max(0, response.outputTokens());
            }
            return response;
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public String getProviderName() {
            return delegate.getProviderName();
        }

        @Override
        public int maxContextWindow() {
            return delegate.maxContextWindow();
        }

        int callCount() {
            return calls;
        }

        int inputTokens() {
            return inputTokens;
        }

        int outputTokens() {
            return outputTokens;
        }
    }
}
