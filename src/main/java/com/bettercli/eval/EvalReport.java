package com.bettercli.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Eval 对比报告：成功率 / 延迟分位 / token。最小闭环先产出单版表。
 */
public record EvalReport(List<TaskRunResult> results) {

    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static EvalReport from(List<TaskRunResult> results) {
        return new EvalReport(results);
    }

    public int total() {
        return results.size();
    }

    public int passed() {
        int n = 0;
        for (TaskRunResult r : results) {
            if (r.success()) {
                n++;
            }
        }
        return n;
    }

    public double successRate() {
        return total() == 0 ? 0.0 : (double) passed() / total();
    }

    public long latencyP50() {
        return percentile(0.50);
    }

    public long latencyP95() {
        return percentile(0.95);
    }

    public int totalInputTokens() {
        return results.stream().mapToInt(TaskRunResult::inputTokens).sum();
    }

    public int totalOutputTokens() {
        return results.stream().mapToInt(TaskRunResult::outputTokens).sum();
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# BetterCLI Agent Eval Report\n\n");
        sb.append(String.format(Locale.ROOT,
                "- tasks: %d\n- passed: %d\n- success_rate: %.1f%%\n- latency_p50_ms: %d\n- latency_p95_ms: %d\n- input_tokens: %d\n- output_tokens: %d\n\n",
                total(), passed(), successRate() * 100.0, latencyP50(), latencyP95(),
                totalInputTokens(), totalOutputTokens()));
        sb.append("| id | mode | success | llm_calls | elapsed_ms | details |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (TaskRunResult r : results) {
            sb.append("| ").append(r.taskId())
                    .append(" | ").append(r.mode())
                    .append(" | ").append(r.success() ? "PASS" : "FAIL")
                    .append(" | ").append(r.llmCalls())
                    .append(" | ").append(r.elapsedMillis())
                    .append(" | ").append(String.join("; ", r.scoreDetails()).replace("|", "\\|"))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private long percentile(double p) {
        if (results.isEmpty()) {
            return 0L;
        }
        List<Long> values = new ArrayList<>();
        for (TaskRunResult r : results) {
            values.add(r.elapsedMillis());
        }
        values.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(p * values.size()) - 1;
        idx = Math.max(0, Math.min(values.size() - 1, idx));
        return values.get(idx);
    }
}
