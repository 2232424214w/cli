package com.bettercli.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryQueryModelsTest {

    @Test
    void memorySearchQueryDefaultsAndClamping() {
        MemorySearchQuery query = MemorySearchQuery.builder()
                .query("test query")
                .build();

        assertEquals("test query", query.getQuery());
        assertEquals(5, query.getLimit());
        assertNull(query.getType());
        assertNull(query.getScope());
        assertNull(query.getProject());
        assertNull(query.getMinConfidence());
        assertNull(query.getCreatedAfter());
        assertNull(query.getCreatedBefore());
        assertFalse(query.isIncludePending());
    }

    @Test
    void memorySearchQueryClampsLimitTo50() {
        MemorySearchQuery highLimit = MemorySearchQuery.builder()
                .query("test")
                .limit(100)
                .build();
        MemorySearchQuery zeroLimit = MemorySearchQuery.builder()
                .query("test")
                .limit(0)
                .build();
        MemorySearchQuery negativeLimit = MemorySearchQuery.builder()
                .query("test")
                .limit(-5)
                .build();

        assertEquals(50, highLimit.getLimit());
        assertEquals(5, zeroLimit.getLimit());
        assertEquals(5, negativeLimit.getLimit());
    }

    @Test
    void memorySearchQueryAllFields() {
        Instant after = Instant.parse("2026-01-01T00:00:00Z");
        Instant before = Instant.parse("2026-07-19T00:00:00Z");

        MemorySearchQuery query = MemorySearchQuery.builder()
                .query("数据库选型")
                .limit(10)
                .type(AgentMemoryEntry.MemoryType.FACT)
                .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                .project("/path/to/project")
                .minConfidence(0.7)
                .createdAfter(after)
                .createdBefore(before)
                .includePending(true)
                .build();

        assertEquals(10, query.getLimit());
        assertEquals(AgentMemoryEntry.MemoryType.FACT, query.getType());
        assertEquals(AgentMemoryEntry.MemoryScope.PROJECT, query.getScope());
        assertEquals("/path/to/project", query.getProject());
        assertEquals(0.7, query.getMinConfidence());
        assertEquals(after, query.getCreatedAfter());
        assertEquals(before, query.getCreatedBefore());
        assertTrue(query.isIncludePending());
    }

    @Test
    void memoryListQueryDefaultsAndClamping() {
        MemoryListQuery query = MemoryListQuery.builder().build();

        assertEquals(0, query.getOffset());
        assertEquals(50, query.getLimit());
        assertEquals("created_at", query.getOrderBy());
        assertNull(query.getType());
        assertNull(query.getScope());
        assertNull(query.getProject());
        assertNull(query.getStatus());
    }

    @Test
    void memoryListQueryClampsOffsetAndLimit() {
        MemoryListQuery negativeOffset = MemoryListQuery.builder()
                .offset(-10)
                .build();
        MemoryListQuery highLimit = MemoryListQuery.builder()
                .limit(500)
                .build();
        MemoryListQuery zeroLimit = MemoryListQuery.builder()
                .limit(0)
                .build();

        assertEquals(0, negativeOffset.getOffset());
        assertEquals(200, highLimit.getLimit());
        assertEquals(50, zeroLimit.getLimit());
    }

    @Test
    void memoryListQueryCustomOrderBy() {
        MemoryListQuery query = MemoryListQuery.builder()
                .orderBy("confidence")
                .build();

        assertEquals("confidence", query.getOrderBy());
    }

    @Test
    void memoryListQueryBlankOrderByDefaultsToCreatedAt() {
        MemoryListQuery blankOrder = MemoryListQuery.builder()
                .orderBy("   ")
                .build();
        MemoryListQuery nullOrder = MemoryListQuery.builder()
                .orderBy(null)
                .build();

        assertEquals("created_at", blankOrder.getOrderBy());
        assertEquals("created_at", nullOrder.getOrderBy());
    }

    @Test
    void memoryStatsFormatForCli() {
        MemoryStats stats = new MemoryStats(100, 90, 5, 5, 80, 20, 5000, 200, 0.75);

        String formatted = stats.formatForCli();
        assertTrue(formatted.contains("总条目: 100"));
        assertTrue(formatted.contains("active=90"));
        assertTrue(formatted.contains("pending=5"));
        assertTrue(formatted.contains("expired=5"));
        assertTrue(formatted.contains("project=80"));
        assertTrue(formatted.contains("global=20"));
        assertTrue(formatted.contains("总 token: 5000"));
        assertTrue(formatted.contains("总访问次数: 200"));
        assertTrue(formatted.contains("平均置信度: 0.75"));
    }

    @Test
    void memoryStatsEmpty() {
        MemoryStats empty = MemoryStats.empty();

        assertEquals(0, empty.totalEntries());
        assertEquals(0, empty.activeEntries());
        assertEquals(0, empty.totalTokens());
        assertEquals(0.0, empty.averageConfidence());
    }

    @Test
    void memoryEntryPatchIsEmptyByDefault() {
        MemoryEntryPatch emptyPatch = MemoryEntryPatch.builder().build();

        assertTrue(emptyPatch.isEmpty());
    }

    @Test
    void memoryEntryPatchNotEmptyWhenAnyFieldSet() {
        MemoryEntryPatch contentPatch = MemoryEntryPatch.builder()
                .content("new content")
                .build();
        MemoryEntryPatch keywordsPatch = MemoryEntryPatch.builder()
                .keywords(List.of("kw1"))
                .build();
        MemoryEntryPatch statusPatch = MemoryEntryPatch.builder()
                .status(AgentMemoryEntry.MemoryStatus.EXPIRED)
                .build();

        assertFalse(contentPatch.isEmpty());
        assertFalse(keywordsPatch.isEmpty());
        assertFalse(statusPatch.isEmpty());
    }

    @Test
    void memoryEntryPatchAllFields() {
        MemoryEntryPatch patch = MemoryEntryPatch.builder()
                .content("updated content")
                .keywords(List.of("new_kw1", "new_kw2"))
                .type(AgentMemoryEntry.MemoryType.PATTERN)
                .scope(AgentMemoryEntry.MemoryScope.GLOBAL)
                .confidence(0.9)
                .status(AgentMemoryEntry.MemoryStatus.ACTIVE)
                .build();

        assertEquals("updated content", patch.getContent());
        assertEquals(List.of("new_kw1", "new_kw2"), patch.getKeywords());
        assertEquals(AgentMemoryEntry.MemoryType.PATTERN, patch.getType());
        assertEquals(AgentMemoryEntry.MemoryScope.GLOBAL, patch.getScope());
        assertEquals(0.9, patch.getConfidence());
        assertEquals(AgentMemoryEntry.MemoryStatus.ACTIVE, patch.getStatus());
        assertFalse(patch.isEmpty());
    }

    @Test
    void memorySearchResultComputesFinalScore() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test")
                .content("test content")
                .confidence(0.8)
                .build();
        MemorySearchResult result = new MemorySearchResult(entry, 2.5, 1.8);

        assertEquals(2.5, result.bm25Score());
        assertEquals(1.8, result.confidenceWeight());
        assertEquals(2.5 * 1.8, result.finalScore());
    }

    @Test
    void memorySearchResultFormatForTool() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-123")
                .content("测试内容")
                .keywords(List.of("kw1", "kw2"))
                .confidence(0.85)
                .type(AgentMemoryEntry.MemoryType.FACT)
                .scope(AgentMemoryEntry.MemoryScope.PROJECT)
                .project("/path/to/project")
                .build();
        MemorySearchResult result = new MemorySearchResult(entry, 1.5, 1.85);

        String formatted = result.formatForTool();
        assertTrue(formatted.contains("[FACT]"));
        assertTrue(formatted.contains("id=test-123"));
        assertTrue(formatted.contains("测试内容"));
        assertTrue(formatted.contains("kw1"));
        assertTrue(formatted.contains("PROJECT"));
        assertTrue(formatted.contains("/path/to/project"));
    }
}
