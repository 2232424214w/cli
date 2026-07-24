package com.bettercli.hitl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolRiskClassifierTest {

    @Test
    void classifiesCommonReadTools() {
        assertTrue(McpToolRiskClassifier.isReadOnly("mcp__fs__read_file"));
        assertTrue(McpToolRiskClassifier.isReadOnly("mcp__fs__list_dir"));
        assertTrue(McpToolRiskClassifier.isReadOnly("mcp__search__web_search"));
        assertTrue(McpToolRiskClassifier.isReadOnly("mcp__chrome__take_snapshot"));
        assertFalse(McpToolRiskClassifier.requiresApproval("mcp__fs__get_info"));
    }

    @Test
    void classifiesCommonWriteTools() {
        assertFalse(McpToolRiskClassifier.isReadOnly("mcp__fs__write_file"));
        assertFalse(McpToolRiskClassifier.isReadOnly("mcp__chrome__click"));
        assertFalse(McpToolRiskClassifier.isReadOnly("mcp__chrome__navigate_page"));
        assertTrue(McpToolRiskClassifier.requiresApproval("mcp__git__commit"));
    }

    @Test
    void writeKeywordWinsOverReadSubstring() {
        // 含 read 但又含 write → 写入优先
        assertFalse(McpToolRiskClassifier.isReadOnly("mcp__x__read_then_write"));
    }

    @Test
    void unknownLeafRequiresApproval() {
        assertTrue(McpToolRiskClassifier.requiresApproval("mcp__x__do_stuff"));
    }
}
