package com.bettercli.subagent;

import com.bettercli.tool.ToolRegistry;
import com.bettercli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentMemoryToolTest {

    @Test
    void writeSubagentMemoryAppendsWhenContextSet(@TempDir Path tempDir) throws Exception {
        Path memory = tempDir.resolve("MEMORY.md");
        ToolRegistry registry = new ToolRegistry();
        registry.setCustomSubAgentContext(new CustomSubAgentRuntimeContext("sql-analyzer", memory, List.of()));

        var results = registry.executeTools(List.of(
                new ToolInvocation("1", "write_subagent_memory", "{\"entry\":\"prefer EXPLAIN\"}")
        ));
        assertEquals(1, results.size());
        assertTrue(results.get(0).result().contains("已追加"), results.get(0).result());
        assertTrue(Files.readString(memory).contains("prefer EXPLAIN"));
    }

    @Test
    void writeSubagentMemoryFailsWithoutContext() {
        ToolRegistry registry = new ToolRegistry();
        var results = registry.executeTools(List.of(
                new ToolInvocation("1", "write_subagent_memory", "{\"entry\":\"x\"}")
        ));
        assertTrue(results.get(0).result().contains("仅 Custom SubAgent"));
    }

    @Test
    void writeSubagentMemoryHiddenFromMainAgentSchema() {
        ToolRegistry registry = new ToolRegistry();
        boolean visible = registry.getToolDefinitions().stream()
                .anyMatch(t -> "write_subagent_memory".equals(t.name()));
        assertFalse(visible);

        registry.setCustomSubAgentContext(new CustomSubAgentRuntimeContext("a", null, List.of()));
        boolean visibleInCustom = registry.getToolDefinitions().stream()
                .anyMatch(t -> "write_subagent_memory".equals(t.name()));
        assertTrue(visibleInCustom);
    }
}
