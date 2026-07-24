package com.bettercli.tool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModeCapabilityToolsTest {

    @Test
    void createPlanAndRunTeamInvokeHandlers() {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger plans = new AtomicInteger();
        AtomicInteger teams = new AtomicInteger();
        registry.setModeCapabilityHandlers(
                goal -> {
                    plans.incrementAndGet();
                    return "PLAN:" + goal;
                },
                goal -> {
                    teams.incrementAndGet();
                    return "TEAM:" + goal;
                }
        );

        assertTrue(registry.hasTool("create_plan"));
        assertTrue(registry.hasTool("run_team"));
        assertEquals("PLAN:重构模块", registry.executeTool("create_plan", "{\"goal\":\"重构模块\"}"));
        assertEquals("TEAM:并行调研", registry.executeTool("run_team", "{\"goal\":\"并行调研\"}"));
        assertEquals(1, plans.get());
        assertEquals(1, teams.get());
    }

    @Test
    void withoutHandlersReturnsError() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.executeTool("create_plan", "{\"goal\":\"x\"}").contains("未注入"));
        assertTrue(registry.executeTool("run_team", "{\"goal\":\"x\"}").contains("未注入"));
    }
}
