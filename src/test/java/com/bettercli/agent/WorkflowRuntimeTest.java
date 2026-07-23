package com.bettercli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 WorkflowRuntime（对标 Claude Code 2026.6 Dynamic Workflow）：
 * 顺序 / 并行 / 条件 / 循环 / 死循环兜底 / 中间结果存黑板不灌 LLM context。
 *
 * <p>测试用纯函数注入 TaskStep.action，使 runtime 可独立单测，不依赖 LLM/SubAgent。
 */
class WorkflowRuntimeTest {

    @Test
    void executesSequentialTasksAndStoresArtifactsOnBlackboard() {
        WorkflowScript script = new WorkflowScript("顺序任务", List.of(
                new TaskStep("s1", "第一步", st -> "结果1"),
                new TaskStep("s2", "第二步", st -> st.getArtifact("s1") + "+结果2")
        ));
        SharedState state = new SharedState();
        state.setGoal("测试", null);

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertTrue(result.completed());
        assertEquals("结果1", state.getArtifact("s1"));
        assertEquals("结果1+结果2", state.getArtifact("s2"));
        assertEquals(List.of("s1", "s2"), result.executedStepIds());
        // routing 决策入审计
        assertTrue(state.getRoutingLog().size() >= 2);
    }

    @Test
    void executesParallelBranchesConcurrently() throws Exception {
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicInteger current = new AtomicInteger();
        WorkflowScript script = new WorkflowScript("并行", List.of(
                new ParallelStep("p1", List.of(
                        new TaskStep("a", "分支A", st -> blockAndTrack(peakConcurrency, current, "A")),
                        new TaskStep("b", "分支B", st -> blockAndTrack(peakConcurrency, current, "B"))
                ))
        ));
        SharedState state = new SharedState();

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertTrue(result.completed());
        assertEquals("A", state.getArtifact("a"));
        assertEquals("B", state.getArtifact("b"));
        assertTrue(peakConcurrency.get() >= 2, "两个分支应真正并行，峰值并发 >= 2，实际=" + peakConcurrency.get());
    }

    @Test
    void conditionalExecutesThenBranchWhenConditionTrue() {
        WorkflowScript script = new WorkflowScript("条件真", List.of(
                new TaskStep("flag", "设标志", st -> "true"),
                new ConditionalStep("if1",
                        new WorkflowScript.Condition("flag", "true"),
                        List.of(new TaskStep("then", "真分支", st -> "走了then")),
                        List.of(new TaskStep("els", "假分支", st -> "走了else")))
        ));
        SharedState state = new SharedState();

        new WorkflowRuntime().execute(script, state);

        assertEquals("走了then", state.getArtifact("then"));
        assertEquals(null, state.getArtifact("els"));
    }

    @Test
    void conditionalExecutesElseBranchWhenConditionFalse() {
        WorkflowScript script = new WorkflowScript("条件假", List.of(
                new TaskStep("flag", "设标志", st -> "false"),
                new ConditionalStep("if1",
                        new WorkflowScript.Condition("flag", "true"),
                        List.of(new TaskStep("then", "真分支", st -> "走了then")),
                        List.of(new TaskStep("els", "假分支", st -> "走了else")))
        ));
        SharedState state = new SharedState();

        new WorkflowRuntime().execute(script, state);

        assertEquals("走了else", state.getArtifact("els"));
        assertEquals(null, state.getArtifact("then"));
    }

    @Test
    void loopRepeatsUntilConditionMet() {
        // 循环 3 次后 counter==3，condition "counter"=="3" 满足退出
        AtomicInteger counter = new AtomicInteger();
        WorkflowScript script = new WorkflowScript("循环", List.of(
                new LoopStep("loop1",
                        new WorkflowScript.Condition("counter", "3"),
                        10,
                        List.of(new TaskStep("counter", "自增", st -> {
                            int n = counter.incrementAndGet();
                            return String.valueOf(n);
                        })))
        ));
        SharedState state = new SharedState();

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertTrue(result.completed());
        assertEquals(3, counter.get(), "循环体应执行 3 次后条件满足退出");
        assertEquals("3", state.getArtifact("counter"));
    }

    @Test
    void loopAbortsAtMaxIterationsToPreventInfiniteLoop() {
        // condition 永不满足，maxIterations=3 应硬中止
        AtomicInteger counter = new AtomicInteger();
        WorkflowScript script = new WorkflowScript("死循环兜底", List.of(
                new LoopStep("loop1",
                        new WorkflowScript.Condition("counter", "999"),
                        3,
                        List.of(new TaskStep("inc", "自增", st -> {
                            counter.incrementAndGet();
                            return "x";
                        })))
        ));
        SharedState state = new SharedState();

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertFalse(result.completed(), "达到 maxIterations 应中止");
        assertEquals(3, counter.get(), "应执行满 3 次 maxIterations");
        assertTrue(result.summary().contains("maxIterations"));
    }

    @Test
    void taskStepReadsBlackboardAsInput() {
        // 验证 action 能读黑板前序产物（中间结果不灌 LLM context，靠黑板传递）
        WorkflowScript script = new WorkflowScript("黑板传递", List.of(
                new TaskStep("s1", "产数据", st -> "DATA"),
                new TaskStep("s2", "用数据", st -> "GOT:" + st.getArtifact("s1"))
        ));
        SharedState state = new SharedState();

        new WorkflowRuntime().execute(script, state);

        assertEquals("GOT:DATA", state.getArtifact("s2"));
    }

    @Test
    void abortsWhenTaskThrowsAndPreservesPriorArtifacts() {
        WorkflowScript script = new WorkflowScript("异常中止", List.of(
                new TaskStep("s1", "成功", st -> "OK"),
                new TaskStep("s2", "抛异常", st -> { throw new RuntimeException("boom"); }),
                new TaskStep("s3", "不应执行", st -> "NOPE")
        ));
        SharedState state = new SharedState();

        WorkflowScript.WorkflowResult result = new WorkflowRuntime().execute(script, state);

        assertFalse(result.completed());
        assertTrue(result.summary().contains("boom"));
        assertEquals("OK", state.getArtifact("s1"), "中止前已写入的产物应保留");
        assertEquals(null, state.getArtifact("s3"), "异常后不应继续执行");
    }

    @Test
    void nullScriptReturnsAborted() {
        WorkflowScript.WorkflowResult r = new WorkflowRuntime().execute(null, new SharedState());
        assertFalse(r.completed());
    }

    @Test
    void nullStateReturnsAborted() {
        WorkflowScript.WorkflowResult r = new WorkflowRuntime().execute(
                new WorkflowScript("g", List.of()), null);
        assertFalse(r.completed());
    }

    @Test
    void taskStepRejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskStep("", "x", st -> "y"));
    }

    @Test
    void loopStepRejectsZeroMaxIterations() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoopStep("l", new WorkflowScript.Condition("c", "v"), 0,
                        List.of(new TaskStep("b", "x", st -> "y"))));
    }

    private static String blockAndTrack(AtomicInteger peak, AtomicInteger current, String tag) {
        current.incrementAndGet();
        try {
            Thread.sleep(80);
            peak.accumulateAndGet(current.get(), Math::max);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return tag;
    }
}
