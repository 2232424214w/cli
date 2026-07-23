package com.bettercli.agent;

import com.bettercli.runtime.task.DurableTaskManager;
import com.bettercli.runtime.task.DurableTask;
import com.bettercli.runtime.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段 3：Workflow checkpoint 断点续跑 + DurableTask 完成回推。
 */
class DurableWorkflowResumeTest {

    @Test
    void resumesFromCheckpointSkippingCompletedSteps(@TempDir Path tempDir) throws Exception {
        AtomicInteger s1Calls = new AtomicInteger();
        AtomicInteger s2Calls = new AtomicInteger();
        AtomicBoolean failS2 = new AtomicBoolean(true);

        DurableWorkflowBridge bridge = new DurableWorkflowBridge(prompt -> new WorkflowScript(prompt, List.of(
                new TaskStep("s1", "第一步", st -> {
                    s1Calls.incrementAndGet();
                    return "A";
                }),
                new TaskStep("s2", "第二步", st -> {
                    s2Calls.incrementAndGet();
                    if (failS2.get()) {
                        throw new RuntimeException("模拟中途崩溃");
                    }
                    return st.getArtifact("s1") + "+B";
                })
        )), tempDir.resolve("ckpts"));

        String taskId = "task_resume_1";
        try {
            bridge.run(taskId, "断点续跑测试");
            fail("第一次应因 s2 崩溃而失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("崩溃") || expected.getMessage().contains("中止"));
        }

        assertEquals(1, s1Calls.get());
        assertEquals(1, s2Calls.get());
        assertTrue(bridge.checkpointStore().load(taskId).isPresent(), "崩溃后应保留 checkpoint");
        assertEquals(List.of("s1"), bridge.checkpointStore().load(taskId).orElseThrow().executedStepIds());

        failS2.set(false);
        String result = bridge.run(taskId, "断点续跑测试");

        assertEquals("A+B", result);
        assertEquals(1, s1Calls.get(), "续跑时应跳过 s1，不再执行");
        assertEquals(2, s2Calls.get(), "s2 应再执行一次并成功");
        assertTrue(bridge.checkpointStore().load(taskId).isEmpty(), "成功完成后应清除 checkpoint");
    }

    @Test
    void durableTaskNotifiesCompletionListener(@TempDir Path tempDir) throws Exception {
        AtomicReference<DurableTask> notified = new AtomicReference<>();
        DurableWorkflowBridge bridge = new DurableWorkflowBridge(
                prompt -> new WorkflowScript(prompt, List.of(
                        new TaskStep("only", "单步", st -> "结果:" + prompt)
                )),
                tempDir.resolve("ckpts"));

        try (DurableTaskManager manager = new DurableTaskManager(
                tempDir.resolve("tasks.db"), bridge, 1)) {
            manager.setCompletionListener(notified::set);
            manager.start();
            DurableTask task = manager.enqueue("hello-durable");
            DurableTask done = waitForTerminal(manager, task.id());
            assertEquals(TaskStatus.COMPLETED, done.status());
            assertEquals("结果:hello-durable", done.result());
        }

        assertNotNull(notified.get(), "终态应触发 CompletionListener");
        assertEquals(TaskStatus.COMPLETED, notified.get().status());
        assertEquals("结果:hello-durable", notified.get().result());
    }

    @Test
    void checkpointStoreRoundTrip(@TempDir Path tempDir) throws Exception {
        WorkflowCheckpointStore store = new WorkflowCheckpointStore(tempDir);
        SharedState state = new SharedState();
        state.setGoal("g", null);
        state.putArtifactByRuntime("s1", "v1");
        WorkflowCheckpoint cp = WorkflowCheckpoint.capture("run1", state, List.of("s1"), "snap-abc");
        store.save(cp);

        WorkflowCheckpoint loaded = store.load("run1").orElseThrow();
        assertEquals("g", loaded.goal());
        assertEquals("v1", loaded.artifacts().get("s1"));
        assertEquals("snap-abc", loaded.snapshotId());
        assertEquals(List.of("s1"), loaded.executedStepIds());

        SharedState restored = new SharedState();
        loaded.restoreInto(restored);
        assertEquals("g", restored.getGoal());
        assertEquals("v1", restored.getArtifact("s1"));
    }

    private static DurableTask waitForTerminal(DurableTaskManager manager, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            DurableTask task = manager.find(id).orElseThrow();
            if (task.terminal()) {
                return task;
            }
            Thread.sleep(20);
        }
        fail("task did not finish in time");
        return null;
    }
}
