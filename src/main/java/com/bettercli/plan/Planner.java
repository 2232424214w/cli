package com.bettercli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmTraceLogger;
import com.bettercli.prompt.PromptAssembler;
import com.bettercli.prompt.PromptContext;
import com.bettercli.prompt.PromptMode;
import com.bettercli.prompt.ProjectMemoryLoader;
import com.bettercli.util.AnsiStyle;
import com.bettercli.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * 规划器 - 使用LLM将复杂任务分解为执行计划
 */
public class Planner {
    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private final LlmClient llmClient;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private Supplier<String> projectMemorySupplier = () ->
            ProjectMemoryLoader.createDefault(Path.of(".").toAbsolutePath().normalize()).loadForPrompt();

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    public void setProjectMemorySupplier(Supplier<String> projectMemorySupplier) {
        this.projectMemorySupplier = projectMemorySupplier == null ? () -> "" : projectMemorySupplier;
    }

    /**
     * 为复杂任务创建执行计划
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        return createPlan(goal, null);
    }

    /**
     * 创建执行计划，可附带额外上下文（用于 replan：保留原目标，只把失败上下文作为补充）。
     */
    ExecutionPlan createPlan(String goal, String extraContext) throws IOException {
        out.println("📋 正在规划任务: " + goal + "\n");

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        // 构建规划请求
        String userMessage = "请为以下任务制定执行计划：\n" + goal;
        if (extraContext != null && !extraContext.isBlank()) {
            userMessage += "\n\n" + extraContext;
        }
        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(promptAssembler.assemble(PromptMode.PLANNER, PromptContext.builder()
                        .projectMemoryContext(buildProjectMemoryContext())
                        .build())),
                LlmClient.Message.user(userMessage)
        );

        // 调用LLM生成计划
        PlanningStreamRenderer streamRenderer = new PlanningStreamRenderer(out);
        LlmClient.ChatResponse response = llmClient.chat(messages, null, streamRenderer);
        LlmTraceLogger.logReasoning(log, "planner", llmClient, response.reasoningContent());
        streamRenderer.finish();
        String planJson = response.content();

        // 解析JSON计划
        return parsePlan(goal, planJson);
    }

    private String buildProjectMemoryContext() {
        try {
            String context = projectMemorySupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to load BETTER.md project memory for planner", e);
            return "";
        }
    }

    /**
     * 解析LLM生成的计划JSON
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        // 清理可能的markdown代码块
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // 第一遍：创建所有任务（不处理依赖，因为可能有前向引用）
        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText();
            Task.TaskType type = parseTaskType(typeStr);

            plan.addTask(new Task(newId, description, type));
        }

        // 第二遍：建立依赖和被依赖关系（解析期自动修复 LLM 不可靠输出）
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    // 自依赖：可恢复，直接丢弃并告警
                    if (newDepId.equals(newId)) {
                        log.warn("Plan task {} self-depends on {}, dropping", newId, newDepId);
                        continue;
                    }
                    Task dep = plan.getTask(newDepId);
                    // 悬空依赖：指向不存在的任务，可恢复，丢弃并告警，避免静默丢边
                    if (dep == null) {
                        log.warn("Plan task {} depends on unknown task {}, dropping dangling dependency",
                                newId, newDepId);
                        continue;
                    }
                    task.addDependency(newDepId);
                    dep.addDependent(task.getId());
                }
            }
        }

        // 计算执行顺序；若仍有环，给出可定位的诊断（而非"存在循环依赖"）
        if (!plan.computeExecutionOrder()) {
            List<String> cycle = plan.detectCycle();
            throw new IOException("计划中存在循环依赖: " + String.join(" -> ", cycle));
        }

        return plan;
    }

    /**
     * 解析任务类型
     */
    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    /**
     * 生成计划ID
     */
    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 根据执行结果重新规划。保留原计划目标（不让失败上下文变成新目标），
     * 把已完成任务和失败原因作为额外上下文带给规划者。
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        out.println("🔄 重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("上次执行失败，原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题，不要重复已完成的任务。");

        return createPlan(failedPlan.getGoal(), context.toString());
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        // 修复运算符优先级 bug：原 contains("查看") && contains("文件") 因 && 优先级高于 ||，
        // 导致"读取X"/"打开X"被判 FILE_READ 而"查看X"只有同时含"文件"才判 FILE_READ，三者不一致。
        // 现统一：任一读取类动词即 FILE_READ。
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        private PlanningStreamRenderer(PrintStream out) {
            this.out = out == null ? System.out : out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                out.println("\n");
            }
        }
    }
}
