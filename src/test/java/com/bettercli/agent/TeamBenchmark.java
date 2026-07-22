package com.bettercli.agent;

import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmClientFactory;
import com.bettercli.memory.MemoryManager;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Agent ablation benchmark：单 Agent vs Multi-Agent baseline vs Multi-Agent full。
 *
 * 默认禁用（需真实 API Key + 会产生费用）。启用方式：
 *   mvn -DskipTests=false -Dtest=TeamBenchmark -Dbettercli.benchmark.enabled=true test
 *
 * 输出：docs/multi-agent-ablation-results.md（自动生成结果表格）。
 * 方法论见 docs/multi-agent-ablation.md。
 */
@EnabledIfSystemProperty(named = "bettercli.benchmark.enabled", matches = "true", disabledReason = "benchmark disabled by default; set -Dbettercli.benchmark.enabled=true to run")
class TeamBenchmark {

    record BenchTask(String name, String complexity, String prompt) {}

    private static final List<BenchTask> TASKS = List.of(
            new BenchTask("总结 README", "simple", "读取当前项目的 README.md 并用 3 句话总结"),
            new BenchTask("统计 Java 文件", "simple", "统计 src/main/java 下有多少个 .java 文件，列出数字即可"),
            new BenchTask("解释一个类", "medium", "读 ToolRegistry.java，解释 executeTools 方法的执行流程，用中文"),
            new BenchTask("加一个工具的步骤", "complex", "如果要给 ToolRegistry 新增一个 delete_file 工具，需要改哪些文件、分几步？给出步骤清单，不要真的改"),
            new BenchTask("跨模块梳理", "complex", "梳理 Agent / PlanExecuteAgent / AgentOrchestrator 三条路径如何共享 ToolRegistry，给出文字说明")
    );

    @Test
    void runBenchmark() throws Exception {
        BetterCliConfig config = BetterCliConfig.load();
        LlmClient realClient = LlmClientFactory.createFromConfig(config);
        if (realClient == null) {
            System.out.println("⚠️ 无可用 API Key，benchmark 跳过");
            return;
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"task", "complexity", "mode", "inputTok", "outputTok", "elapsedMs", "success", "llmCalls"});

        for (BenchTask task : TASKS) {
            runMode(rows, task, "A Single", "single", config, realClient);
            runMode(rows, task, "B Multi-base", "multiBase", config, realClient);
            runMode(rows, task, "C Multi-full", "multiFull", config, realClient);
        }

        writeReport(rows);
    }

    private void runMode(List<String[]> rows, BenchTask task, String modeLabel, String mode,
                         BetterCliConfig config, LlmClient realClient) {
        // 每次跑都用新的计数包装 + 新的工具/记忆，保证度量独立
        CountingLlmClient counter = new CountingLlmClient(realClient);
        long start = System.nanoTime();
        String result = "";
        try {
            result = switch (mode) {
                case "single" -> runSingle(counter);
                case "multiBase" -> runMulti(counter, config, false);
                case "multiFull" -> runMulti(counter, config, true);
                default -> "";
            };
        } catch (Exception e) {
            result = "❌ 异常: " + e.getMessage();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        boolean success = !result.contains("❌") && !result.contains("⏹️") && !result.isBlank();
        rows.add(new String[]{
                task.name(), task.complexity(), modeLabel,
                String.valueOf(counter.getInputTokens()),
                String.valueOf(counter.getOutputTokens()),
                String.valueOf(elapsedMs),
                success ? "yes" : "no",
                String.valueOf(counter.getCallCount())
        });
        System.out.printf("[%s][%s] %s -> in=%d out=%d %dms success=%s%n",
                modeLabel, task.complexity(), task.name(),
                counter.getInputTokens(), counter.getOutputTokens(), elapsedMs, success);
    }

    private String runSingle(CountingLlmClient counter) {
        Agent agent = new Agent(counter, new ToolRegistry());
        agent.setRenderer(new com.bettercli.render.PlainRenderer());
        return agent.run("benchmark task");
    }

    private String runMulti(CountingLlmClient counter, BetterCliConfig config, boolean full) {
        ToolRegistry tools = new ToolRegistry();
        MemoryManager memory = new MemoryManager(counter);
        PrintStream sink = new PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        AgentOrchestrator orchestrator = new AgentOrchestrator(counter, tools, memory, sink);
        if (full) {
            // 阶段 B：角色级模型（若配了 BETTERCLI_TEAM_*_PROVIDER 则生效，否则回退主模型）
            orchestrator.setRoleClientResolver(new RoleModelResolver(counter, config));
            // 阶段 C：Worker 专长 + 指派路由 + 持久记忆（持久记忆默认即开）
            orchestrator.setWorkerSpecialties(List.of(
                    "偏实现/编码（FILE_WRITE / COMMAND 类步骤优先）",
                    "偏分析/验证（ANALYSIS / VERIFICATION / FILE_READ 类步骤优先）"
            ));
        }
        return orchestrator.run("benchmark task");
    }

    private void writeReport(List<String[]> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Multi-Agent Ablation 结果\n\n");
        sb.append("> 由 `TeamBenchmark` 自动生成。方法论见 `docs/multi-agent-ablation.md`。\n\n");
        sb.append("| ");
        sb.append(String.join(" | ", rows.get(0)));
        sb.append(" |\n|");
        for (int i = 0; i < rows.get(0).length; i++) {
            sb.append("------|");
        }
        sb.append("\n");
        for (int r = 1; r < rows.size(); r++) {
            sb.append("| ");
            sb.append(String.join(" | ", rows.get(r)));
            sb.append(" |\n");
        }
        Path out = Path.of("docs/multi-agent-ablation-results.md");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("📄 报告已写入: " + out.toAbsolutePath());
    }
}
