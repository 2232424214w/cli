package com.bettercli.tool;

import com.bettercli.agent.PlanStore;
import com.bettercli.agent.ReActPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 update_plan 工具：markdown checkbox 解析 → PlanStore.replace → checkbox 视图返回。
 * 对标 Claude Code TodoWrite 的 replace 语义。
 */
class UpdatePlanToolTest {

    @Test
    void updatePlanParsesCheckboxTasksAndUpdatesStore() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);

        String tasks = String.join("\n",
                "[ ] 读取 auth 模块",
                "[~] 重构 token 校验",
                "[x] 补测试"
        );
        String result = registry.executeTool("update_plan", "{\"tasks\":\"" + escape(tasks) + "\"}");

        assertTrue(result.contains("计划已更新"), "应返回更新成功提示");
        assertTrue(result.contains("1/3"), "应显示完成进度 1/3");
        assertEquals(3, store.size());
        assertEquals(ReActPlan.Status.PENDING, store.snapshot().get(0).status());
        assertEquals(ReActPlan.Status.IN_PROGRESS, store.snapshot().get(1).status());
        assertEquals(ReActPlan.Status.COMPLETED, store.snapshot().get(2).status());
    }

    @Test
    void updatePlanAcceptsAlternativeStatusMarkers() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);

        // x / ~ / 空格 都应被识别
        String tasks = String.join("\n",
                "[x] 已完成",
                "[~] 进行中",
                "[ ] 待办",
                "[>] 也算进行中"
        );
        registry.executeTool("update_plan", "{\"tasks\":\"" + escape(tasks) + "\"}");

        assertEquals(4, store.size());
        assertEquals(ReActPlan.Status.COMPLETED, store.snapshot().get(0).status());
        assertEquals(ReActPlan.Status.IN_PROGRESS, store.snapshot().get(1).status());
        assertEquals(ReActPlan.Status.PENDING, store.snapshot().get(2).status());
        assertEquals(ReActPlan.Status.IN_PROGRESS, store.snapshot().get(3).status());
    }

    @Test
    void updatePlanLinesWithoutMarkerDefaultToPending() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);

        String tasks = "没有标记的任务\n[ ] 有标记的任务";
        registry.executeTool("update_plan", "{\"tasks\":\"" + escape(tasks) + "\"}");

        assertEquals(2, store.size());
        assertEquals(ReActPlan.Status.PENDING, store.snapshot().get(0).status());
        assertEquals("没有标记的任务", store.snapshot().get(0).content());
    }

    @Test
    void updatePlanEmptyTasksClearsStore() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);
        store.replace(java.util.List.of(ReActPlan.of("1", "旧任务")));

        String result = registry.executeTool("update_plan", "{\"tasks\":\"\"}");

        assertTrue(result.contains("已清空"));
        assertTrue(store.isEmpty());
    }

    @Test
    void updatePlanBlankTasksClearsStore() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);
        store.replace(java.util.List.of(ReActPlan.of("1", "旧任务")));

        registry.executeTool("update_plan", "{\"tasks\":\"   \"}");

        assertTrue(store.isEmpty(), "纯空白 tasks 应等价于清空");
    }

    @Test
    void updatePlanSkipsBlankLinesAndEmptyContent() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);

        String tasks = String.join("\n",
                "[ ] 第一个",
                "",
                "   ",
                "[ ] ",
                "[ ] 第二个"
        );
        registry.executeTool("update_plan", "{\"tasks\":\"" + escape(tasks) + "\"}");

        assertEquals(2, store.size(), "空行和空内容应被跳过");
    }

    @Test
    void updatePlanWithoutStoreReturnsNotInitialized() {
        ToolRegistry registry = new ToolRegistry();
        // 不注入 PlanStore
        String result = registry.executeTool("update_plan", "{\"tasks\":\"[ ] 任务\"}");

        assertTrue(result.contains("未初始化"), "未注入 PlanStore 时应返回未初始化提示");
    }

    @Test
    void updatePlanIsReplaceNotIncremental() {
        ToolRegistry registry = new ToolRegistry();
        PlanStore store = new PlanStore();
        registry.setPlanStore(store);

        // 第一次：3 个任务
        registry.executeTool("update_plan", "{\"tasks\":\"" + escape("[ ] a\n[ ] b\n[ ] c") + "\"}");
        assertEquals(3, store.size());

        // 第二次：只传 1 个，应整体覆盖（不是追加）
        registry.executeTool("update_plan", "{\"tasks\":\"" + escape("[ ] only") + "\"}");
        assertEquals(1, store.size());
        assertEquals("only", store.snapshot().get(0).content());
    }

    @Test
    void updatePlanToolExposedInDefinitions() {
        ToolRegistry registry = new ToolRegistry();
        boolean exposed = registry.getToolDefinitions().stream()
                .anyMatch(t -> t.name().equals("update_plan"));
        assertTrue(exposed, "update_plan 应出现在默认工具定义列表");
    }

    @Test
    void updatePlanRespectsWhitelist() {
        ToolRegistry registry = new ToolRegistry();
        // 白名单不含 update_plan 时不应暴露
        boolean exposed = registry.getToolDefinitions(java.util.Set.of("read_file", "grep_code")).stream()
                .anyMatch(t -> t.name().equals("update_plan"));
        assertFalse(exposed, "白名单不含 update_plan 时不应暴露");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
