package com.bettercli.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentAuditTest {

    @TempDir
    Path temp;

    @Test
    void recordAndTail() {
        System.setProperty("bettercli.audit.dir", temp.toString());
        try {
            CustomSubAgentAudit.record("SUBAGENT_STARTED", "code-reviewer", "sub_1", "parent_1", "task");
            CustomSubAgentAudit.record("SUBAGENT_DONE", "code-reviewer", "sub_1", "parent_1", "OK");
            String tail = CustomSubAgentAudit.formatTail(10);
            assertTrue(tail.contains("SUBAGENT_STARTED"));
            assertTrue(tail.contains("code-reviewer"));
            assertTrue(tail.contains("subagent_name") || tail.contains("code-reviewer"));
            assertFalse(CustomSubAgentAudit.readRecentLines(5).isEmpty());
        } finally {
            System.clearProperty("bettercli.audit.dir");
        }
    }
}
