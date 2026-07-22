package com.bettercli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRoleTest {

    @Test
    void shouldHaveThreeRoles() {
        AgentRole[] roles = AgentRole.values();
        assertEquals(3, roles.length);
    }

    @Test
    void shouldHaveCorrectDisplayNames() {
        assertEquals("规划者", AgentRole.PLANNER.getDisplayName());
        assertEquals("执行者", AgentRole.WORKER.getDisplayName());
        assertEquals("检查者", AgentRole.REVIEWER.getDisplayName());
    }

    @Test
    void shouldHaveNonEmptyDescriptions() {
        for (AgentRole role : AgentRole.values()) {
            assertFalse(role.getDescription().isEmpty(),
                    role.name() + " should have a non-empty description");
        }
    }

    @Test
    void shouldValueOfByName() {
        assertSame(AgentRole.PLANNER, AgentRole.valueOf("PLANNER"));
        assertSame(AgentRole.WORKER, AgentRole.valueOf("WORKER"));
        assertSame(AgentRole.REVIEWER, AgentRole.valueOf("REVIEWER"));
    }

    @Test
    void shouldDefineRoleToolWhitelist() {
        // WORKER 不限制（null = 全量）
        assertNull(AgentRole.WORKER.allowedTools(),
                "WORKER should be unrestricted (null = all tools)");

        // PLANNER：只读+调研，禁止写/执行/记忆
        var planner = AgentRole.PLANNER.allowedTools();
        assertNotNull(planner);
        assertTrue(planner.contains("read_file"));
        assertTrue(planner.contains("grep_code"));
        assertTrue(planner.contains("web_search"));
        assertFalse(planner.contains("write_file"));
        assertFalse(planner.contains("execute_command"));
        assertFalse(planner.contains("save_memory"));
        assertFalse(planner.contains("revert_turn"));

        // REVIEWER：纯只读，禁止联网/写/执行
        var reviewer = AgentRole.REVIEWER.allowedTools();
        assertNotNull(reviewer);
        assertTrue(reviewer.contains("read_file"));
        assertTrue(reviewer.contains("glob_files"));
        assertFalse(reviewer.contains("web_search"));
        assertFalse(reviewer.contains("web_fetch"));
        assertFalse(reviewer.contains("write_file"));
        assertFalse(reviewer.contains("execute_command"));

        // 白名单必须不可变，防止外部篡改
        assertThrows(UnsupportedOperationException.class,
                () -> AgentRole.PLANNER.allowedTools().add("execute_command"));
    }
}
