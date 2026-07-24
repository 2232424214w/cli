package com.bettercli.tool;

import com.bettercli.agent.SessionNotebook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotebookToolTest {

    @Test
    void writeAndReadRoundTrip() {
        ToolRegistry registry = new ToolRegistry();
        SessionNotebook notebook = new SessionNotebook();
        registry.setSessionNotebook(notebook);

        String write = registry.executeTool("notebook_write",
                "{\"title\":\"决策\",\"content\":\"使用 RRF 融合\"}");
        assertTrue(write.contains("#1"));
        assertTrue(registry.hasTool("notebook_read"));

        String read = registry.executeTool("notebook_read", "{\"query\":\"RRF\"}");
        assertTrue(read.contains("RRF"));
        assertTrue(read.contains("决策"));
    }

    @Test
    void withoutNotebookReturnsError() {
        ToolRegistry registry = new ToolRegistry();
        String result = registry.executeTool("notebook_write",
                "{\"title\":\"x\",\"content\":\"y\"}");
        assertTrue(result.contains("未初始化"));
    }
}
