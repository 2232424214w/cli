package com.bettercli.agent;

import com.bettercli.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionServiceTest {

    private static ToolExecutionResult result(String name, String text, boolean timedOut) {
        return new ToolExecutionResult("call_1", name, "{}", text, 0, timedOut, null);
    }

    @Test
    void classifiesSuccessWhenNoFailurePrefix() {
        ReflectionService s = new ReflectionService(true, 2);
        assertEquals(ReflectionService.Outcome.SUCCESS, s.classify(result("read_file", "文件内容...", false)));
        assertEquals(ReflectionService.Outcome.SUCCESS, s.classify(null));
    }

    @Test
    void classifiesRejectedByPolicyPrefix() {
        ReflectionService s = new ReflectionService(true, 2);
        assertEquals(ReflectionService.Outcome.REJECTED,
                s.classify(result("grep_code", "🛡️ 策略拒绝: 路径越出项目根", false)));
    }

    @Test
    void classifiesFailedByPrefix() {
        ReflectionService s = new ReflectionService(true, 2);
        assertEquals(ReflectionService.Outcome.FAILED,
                s.classify(result("execute_command", "工具执行失败: 非零退出码", false)));
        assertEquals(ReflectionService.Outcome.FAILED,
                s.classify(result("web_search", "搜索失败: 超时", false)));
        assertEquals(ReflectionService.Outcome.FAILED,
                s.classify(result("web_fetch", "❌ 抓取失败", false)));
        assertEquals(ReflectionService.Outcome.FAILED,
                s.classify(result("read_file", "读取文件失败: 不是普通文件", false)));
    }

    @Test
    void classifiesTimeout() {
        ReflectionService s = new ReflectionService(true, 2);
        assertEquals(ReflectionService.Outcome.TIMEOUT,
                s.classify(result("execute_command", "（结果）", true)));
    }

    @Test
    void classifiesByStructuredToolStatus() {
        ReflectionService s = new ReflectionService(true, 2);
        ToolExecutionResult denied = new ToolExecutionResult(
                "c1", "write_file", "{}", "🛡️ 策略拒绝: x", 0, false, null,
                com.bettercli.tool.ToolStatus.policyDenied());
        ToolExecutionResult missing = new ToolExecutionResult(
                "c2", "read_file", "{}", "读取文件失败: no such file", 0, false, null,
                com.bettercli.tool.ToolStatus.notFound());
        assertEquals(ReflectionService.Outcome.REJECTED, s.classify(denied));
        assertEquals(ReflectionService.Outcome.FAILED, s.classify(missing));
    }

    @Test
    void returnsNullWhenAllSucceedAndResetsCounter() {
        ReflectionService s = new ReflectionService(true, 2);
        assertNotNull(s.buildReflectionPrompt(
                List.of(result("grep_code", "🛡️ 策略拒绝: x", false)), 1));
        assertEquals(1, s.consecutiveReflections());
        // 本轮全成功，应返回 null 并重置计数
        assertNull(s.buildReflectionPrompt(List.of(result("read_file", "ok", false)), 2));
        assertEquals(0, s.consecutiveReflections());
    }

    @Test
    void injectsReflectionPromptOnFailure() {
        ReflectionService s = new ReflectionService(true, 2);
        String prompt = s.buildReflectionPrompt(
                List.of(result("grep_code", "🛡️ 策略拒绝: 路径越出项目根", false)), 3);
        assertNotNull(prompt);
        assertTrue(prompt.contains("[反思提示]"), "应以 [反思提示] 开头");
        assertTrue(prompt.contains("grep_code"), "应包含失败工具名");
        assertTrue(prompt.contains("策略拒绝"), "应包含失败原因");
        assertTrue(prompt.contains("第 3 轮"), "应包含轮次");
        assertEquals(1, s.consecutiveReflections());
    }

    @Test
    void antiSpiralStopsAfterMaxConsecutive() {
        ReflectionService s = new ReflectionService(true, 2);
        // 第1次失败：注入
        assertNotNull(s.buildReflectionPrompt(
                List.of(result("grep_code", "🛡️ 策略拒绝: a", false)), 1));
        assertEquals(1, s.consecutiveReflections());
        // 第2次失败：注入
        assertNotNull(s.buildReflectionPrompt(
                List.of(result("grep_code", "🛡️ 策略拒绝: b", false)), 2));
        assertEquals(2, s.consecutiveReflections());
        // 第3次失败：超阈值，停止注入
        assertNull(s.buildReflectionPrompt(
                List.of(result("grep_code", "🛡️ 策略拒绝: c", false)), 3));
        assertEquals(3, s.consecutiveReflections(), "计数仍递增但不再注入");
    }

    @Test
    void disabledReturnsNull() {
        ReflectionService s = new ReflectionService(false, 2);
        assertNull(s.buildReflectionPrompt(
                List.of(result("grep_code", "🛡️ 策略拒绝: x", false)), 1));
        assertFalse(s.enabled());
    }

    @Test
    void nullOrEmptyResultsReturnNull() {
        ReflectionService s = new ReflectionService(true, 2);
        assertNull(s.buildReflectionPrompt(null, 1));
        assertNull(s.buildReflectionPrompt(List.of(), 1));
    }

    @Test
    void promptIncludesMultipleFailuresAndOmitsSuccess() {
        ReflectionService s = new ReflectionService(true, 2);
        String prompt = s.buildReflectionPrompt(List.of(
                result("read_file", "ok", false),
                result("grep_code", "🛡️ 策略拒绝: x", false),
                result("web_search", "搜索失败: y", false)
        ), 1);
        assertNotNull(prompt);
        assertTrue(prompt.contains("grep_code"));
        assertTrue(prompt.contains("web_search"));
        assertFalse(prompt.contains("read_file"), "成功项不应出现在失败列表");
    }

    @Test
    void incrementalDebateContextEmphasizesPatchNotRedo() {
        String ctx = ReflectionService.buildIncrementalDebateContext(
                "依赖上下文", "上一版结果ABC", "- 缺错误处理", 2);
        assertTrue(ctx.contains("增量辩论"));
        assertTrue(ctx.contains("不要推倒重来"));
        assertTrue(ctx.contains("上一版结果ABC"));
        assertTrue(ctx.contains("缺错误处理"));
        assertTrue(ctx.contains("第 2 轮"));
    }

    @Test
    void debateConvergedOnExplicitFlagOrSameIssues() {
        assertTrue(ReflectionService.isDebateConverged(
                "{\"approved\": false, \"converged\": true, \"issues\": [\"x\"]}", null));
        assertTrue(ReflectionService.isDebateConverged("审查未通过，已收敛", null));
        assertTrue(ReflectionService.isDebateConverged(
                "{\"approved\": false, \"issues\": [\"缺错误处理\"]}",
                "{\"issues\": [\"缺错误处理\"]}"));
        assertFalse(ReflectionService.isDebateConverged(
                "{\"approved\": false, \"issues\": [\"新问题\"]}",
                "{\"issues\": [\"旧问题\"]}"));
        assertFalse(ReflectionService.isDebateConverged(
                "{\"approved\": false, \"issues\": [\"x\"]}", null));
    }
}
