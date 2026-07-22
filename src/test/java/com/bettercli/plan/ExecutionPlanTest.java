package com.bettercli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanTest {

    @Test
    void computeExecutionOrderRespectsDependencies() {
        ExecutionPlan plan = new ExecutionPlan("plan_1", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));
        Task task3 = new Task("task_3", "verify structure", Task.TaskType.VERIFICATION, List.of("task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        assertEquals(List.of("task_1", "task_2", "task_3"), plan.getExecutionOrder());
    }

    @Test
    void executableTasksWaitUntilDependenciesComplete() {
        ExecutionPlan plan = new ExecutionPlan("plan_2", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));

        plan.addTask(task1);
        plan.addTask(task2);

        assertEquals(List.of(task1), plan.getExecutableTasks());

        task1.markCompleted("done");

        assertEquals(List.of(task2), plan.getExecutableTasks());
    }

    @Test
    void addDependencyMutatesTaskState() {
        Task task = new Task("task_1", "read pom", Task.TaskType.FILE_READ);

        task.addDependency("task_0");

        assertEquals(List.of("task_0"), task.getDependencies());
    }

    @Test
    void addTaskBuildsDependentRelationship() {
        ExecutionPlan plan = new ExecutionPlan("plan_3", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));

        plan.addTask(task1);
        plan.addTask(task2);

        assertTrue(plan.getTask("task_1").getDependents().contains("task_2"));
    }

    @Test
    void executableTasksCanExposeParallelBatch() {
        ExecutionPlan plan = new ExecutionPlan("plan_4", "demo");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list dir", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "verify", Task.TaskType.VERIFICATION, List.of("task_1", "task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        assertEquals(List.of(task1, task2), plan.getExecutableTasks());

        task1.markCompleted("done");
        assertEquals(List.of(task2), plan.getExecutableTasks());

        task2.markCompleted("done");
        assertEquals(List.of(task3), plan.getExecutableTasks());
    }

    @Test
    void summarizeKeepsPlanPreviewCompact() {
        ExecutionPlan plan = new ExecutionPlan("plan_5",
                "请把任务拆成可并行的 DAG:\n1. 读取 pom.xml\n2. 列出 src/main/java");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list src main java", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "summarize project", Task.TaskType.ANALYSIS, List.of("task_1", "task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        String summary = plan.summarize();

        assertTrue(summary.contains("任务数: 3 | 并行批次: 2 | 当前可执行: 2"));
        assertTrue(summary.contains("首批执行: task_1, task_2"));
        assertTrue(summary.contains("最终收敛: task_3"));
        assertTrue(!summary.contains("╔════════"));
    }

    @Test
    void executionBatchesFollowDagLayers() {
        ExecutionPlan plan = new ExecutionPlan("plan_6", "demo");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list main", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "list test", Task.TaskType.COMMAND);
        Task task4 = new Task("task_4", "read readme", Task.TaskType.FILE_READ);
        Task task5 = new Task("task_5", "summarize", Task.TaskType.ANALYSIS, List.of("task_1", "task_2", "task_3", "task_4"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);
        plan.addTask(task4);
        plan.addTask(task5);

        List<List<Task>> batches = plan.getExecutionBatches();

        assertEquals(List.of(task1, task2, task3, task4), batches.get(0));
        assertEquals(List.of(task5), batches.get(1));
    }

    @Test
    void detectCycleReturnsEmptyForAcyclicPlan() {
        ExecutionPlan plan = new ExecutionPlan("plan_7", "demo");
        plan.addTask(new Task("task_1", "a", Task.TaskType.COMMAND));
        plan.addTask(new Task("task_2", "b", Task.TaskType.FILE_READ, List.of("task_1")));

        assertTrue(plan.detectCycle().isEmpty());
        assertTrue(plan.validate().valid());
    }

    @Test
    void detectCycleReturnsPathForCircularDependency() {
        ExecutionPlan plan = new ExecutionPlan("plan_8", "demo");
        // task_1 -> task_2 -> task_3 -> task_1 (循环依赖)
        plan.addTask(new Task("task_1", "a", Task.TaskType.COMMAND, List.of("task_3")));
        plan.addTask(new Task("task_2", "b", Task.TaskType.COMMAND, List.of("task_1")));
        plan.addTask(new Task("task_3", "c", Task.TaskType.COMMAND, List.of("task_2")));

        List<String> cycle = plan.detectCycle();
        assertFalse(cycle.isEmpty(), "循环依赖应被检测到");
        // 环路径应包含全部三个任务
        assertEquals(3, cycle.size());
        assertTrue(cycle.containsAll(List.of("task_1", "task_2", "task_3")));
        assertFalse(plan.validate().valid());
    }

    @Test
    void validateFlagsDanglingAndSelfDependencies() {
        ExecutionPlan plan = new ExecutionPlan("plan_9", "demo");
        Task task1 = new Task("task_1", "a", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "b", Task.TaskType.COMMAND, List.of("task_99")); // 悬空
        Task task3 = new Task("task_3", "c", Task.TaskType.COMMAND);
        task3.addDependency("task_3"); // 自依赖
        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        PlanValidationResult result = plan.validate();
        assertFalse(result.valid());
        assertEquals(1, result.danglingDependencies().size(), "应检测到 1 个悬空依赖");
        assertTrue(result.danglingDependencies().get(0).contains("task_2"));
        assertEquals(1, result.selfDependencies().size(), "应检测到 1 个自依赖");
        assertEquals("task_3", result.selfDependencies().get(0));
        assertTrue(result.cycle().isEmpty(), "悬空/自依赖不应被误判为环");
    }
}
