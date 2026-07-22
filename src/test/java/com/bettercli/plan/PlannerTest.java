package com.bettercli.plan;

import com.bettercli.llm.GLMClient;
import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void createsMinimalPlanForSimpleGoalWithoutCallingLlm() throws Exception {
        Planner planner = new Planner(new FailingGLMClient());

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals("直接执行简单任务：列出当前目录的文件", plan.getSummary());
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        Task task = plan.getTask("task_1");
        assertEquals(Task.TaskType.COMMAND, task.getType());
        assertEquals("列出当前目录的文件", task.getDescription());
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        planner.setProjectMemorySupplier(() -> "## BETTER.md 项目记忆\n- 计划前必须读取项目规则");

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
        assertTrue(client.lastSystemPrompt.contains("计划前必须读取项目规则"));
    }

    @Test
    void parsePlanAutoRepairsSelfAndDanglingDependencies() throws Exception {
        // task_1 自依赖；task_2 依赖不存在的 task_99（悬空）。两者都应被丢弃，计划可正常执行。
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "修复演示",
                  "tasks": [
                    { "id": "task_a", "description": "a", "type": "COMMAND", "dependencies": ["task_a"] },
                    { "id": "task_b", "description": "b", "type": "COMMAND", "dependencies": ["task_99"] }
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan plan = planner.createPlan("先做 a 然后做 b 这个复杂任务");

        assertTrue(plan.getTask("task_1").getDependencies().isEmpty(), "自依赖应被自动丢弃");
        assertTrue(plan.getTask("task_2").getDependencies().isEmpty(), "悬空依赖应被自动丢弃");
        assertEquals(2, plan.getExecutionOrder().size());
    }

    @Test
    void parsePlanThrowsWithCyclePathInsteadOfGenericMessage() {
        // task_1 -> task_2 -> task_1 真环（不可恢复），应抛带路径的 IOException
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "环演示",
                  "tasks": [
                    { "id": "task_a", "description": "a", "type": "COMMAND", "dependencies": ["task_b"] },
                    { "id": "task_b", "description": "b", "type": "COMMAND", "dependencies": ["task_a"] }
                  ]
                }
                """);
        Planner planner = new Planner(client);

        IOException ex = assertThrows(IOException.class,
                () -> planner.createPlan("先做 a 然后做 b 这个复杂任务"));
        assertTrue(ex.getMessage().contains("循环依赖"), "应明确指出循环依赖");
        assertTrue(ex.getMessage().contains("task_1") && ex.getMessage().contains("task_2"),
                "应给出环路径，包含具体任务 id: " + ex.getMessage());
    }

    @Test
    void inferSimpleTaskTypeTreatsViewVerbAsFileRead() throws Exception {
        // 修复优先级 bug 前："查看日志" 不含"文件" → 落到 COMMAND；修复后 → FILE_READ
        Planner planner = new Planner(new FailingGLMClient());

        ExecutionPlan plan = planner.createPlan("查看日志");

        assertEquals(Task.TaskType.FILE_READ, plan.getTask("task_1").getType());
    }

    @Test
    void replanPreservesOriginalGoalInsteadOfUsingFailureContextAsGoal() throws Exception {
        StubGLMClient client = new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    { "id": "task_a", "description": "a", "type": "COMMAND", "dependencies": [] }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        String originalGoal = "先读取 pom.xml 然后验证项目结构";
        ExecutionPlan plan = planner.createPlan(originalGoal);
        plan.getTask("task_1").markCompleted("done");

        ExecutionPlan replanned = planner.replan(plan, "something failed");

        assertEquals(originalGoal, replanned.getGoal(),
                "replan 应保留原目标，而不是把失败上下文当成新目标");
        assertTrue(client.lastUserMessage.contains(originalGoal), "规划请求应包含原目标");
        assertTrue(client.lastUserMessage.contains("something failed"), "规划请求应包含失败原因");
    }

    private static final class FailingGLMClient extends GLMClient {
        private FailingGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("simple goal should not call llm");
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final String content;
        private String lastSystemPrompt = "";
        private String lastUserMessage = "";

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            this.lastSystemPrompt = messages.get(0).content();
            this.lastUserMessage = messages.size() > 1 ? messages.get(1).content() : "";
            return new ChatResponse("assistant", content, null, 100, 20);
        }
    }
}
