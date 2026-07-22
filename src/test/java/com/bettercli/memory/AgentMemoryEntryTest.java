package com.bettercli.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentMemoryEntryTest {

    @Test
    void builderCreatesEntryWithDefaults() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-1")
                .content("项目用 SQLite 不用 PostgreSQL")
                .build();

        assertEquals("test-1", entry.getId());
        assertEquals("项目用 SQLite 不用 PostgreSQL", entry.getContent());
        assertTrue(entry.getKeywords().isEmpty());
        assertEquals(AgentMemoryEntry.MemoryType.FACT, entry.getType());
        assertEquals(AgentMemoryEntry.MemoryScope.PROJECT, entry.getScope());
        assertEquals(0.5, entry.getConfidence());
        assertEquals(AgentMemoryEntry.MemorySource.AGENT_TOOL, entry.getSource());
        assertEquals(AgentMemoryEntry.MemoryStatus.ACTIVE, entry.getStatus());
        assertEquals(0, entry.getAccessCount());
        assertNotNull(entry.getCreatedAt());
        assertNotNull(entry.getUpdatedAt());
        assertTrue(entry.getTokenCount() > 0);
    }

    @Test
    void builderClampsConfidenceToRange() {
        AgentMemoryEntry low = AgentMemoryEntry.builder()
                .id("low").content("x").confidence(-0.5).build();
        AgentMemoryEntry high = AgentMemoryEntry.builder()
                .id("high").content("x").confidence(1.5).build();

        assertEquals(0.0, low.getConfidence());
        assertEquals(1.0, high.getConfidence());
    }

    @Test
    void keywordsJsonRoundTrip() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-2")
                .content("测试内容")
                .keywords(List.of("Paicli", "phase-12", "JDT"))
                .build();

        String json = entry.keywordsJson();
        assertEquals("[\"Paicli\",\"phase-12\",\"JDT\"]", json);

        List<String> parsed = AgentMemoryEntry.parseKeywords(json);
        assertEquals(List.of("Paicli", "phase-12", "JDT"), parsed);
    }

    @Test
    void parseKeywordsHandlesNullAndBlank() {
        assertTrue(AgentMemoryEntry.parseKeywords(null).isEmpty());
        assertTrue(AgentMemoryEntry.parseKeywords("").isEmpty());
        assertTrue(AgentMemoryEntry.parseKeywords("   ").isEmpty());
    }

    @Test
    void parseKeywordsHandlesInvalidJson() {
        assertTrue(AgentMemoryEntry.parseKeywords("not json").isEmpty());
    }

    @Test
    void searchableTextCombinesContentAndKeywords() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-3")
                .content("项目使用 SQLite")
                .keywords(List.of("SQLite", "数据库"))
                .build();

        String searchable = entry.searchableText();
        assertTrue(searchable.contains("项目使用 SQLite"));
        assertTrue(searchable.contains("SQLite"));
        assertTrue(searchable.contains("数据库"));
    }

    @Test
    void searchableTextHandlesEmptyKeywords() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-4")
                .content("只有内容没有关键词")
                .build();

        String searchable = entry.searchableText();
        assertEquals("只有内容没有关键词", searchable);
    }

    @Test
    void estimateTokensHandlesChineseAndEnglish() {
        int chineseTokens = AgentMemoryEntry.estimateTokens("项目使用数据库");
        int englishTokens = AgentMemoryEntry.estimateTokens("project uses database");
        int emptyTokens = AgentMemoryEntry.estimateTokens("");
        int nullTokens = AgentMemoryEntry.estimateTokens(null);

        assertTrue(chineseTokens > 0);
        assertTrue(englishTokens > 0);
        assertEquals(0, emptyTokens);
        assertEquals(0, nullTokens);
    }

    @Test
    void builderAddKeywordAppendsAndTrims() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-5")
                .content("内容")
                .addKeyword("  关键词1  ")
                .addKeyword("关键词2")
                .addKeyword(null)
                .addKeyword("  ")
                .build();

        assertEquals(List.of("关键词1", "关键词2"), entry.getKeywords());
    }

    @Test
    void builderPreservesAllFields() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-07-19T12:00:00Z");
        Instant pendingExpiresAt = Instant.parse("2026-07-20T00:00:00Z");
        Instant lastAccessed = Instant.parse("2026-07-19T11:00:00Z");

        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-6")
                .content("完整字段测试")
                .keywords(List.of("kw1"))
                .type(AgentMemoryEntry.MemoryType.DEBUG_INSIGHT)
                .scope(AgentMemoryEntry.MemoryScope.GLOBAL)
                .project("/path/to/project")
                .confidence(0.85)
                .source(AgentMemoryEntry.MemorySource.EXPLICIT_HINT)
                .status(AgentMemoryEntry.MemoryStatus.PENDING)
                .pendingExpiresAt(pendingExpiresAt)
                .tokenCount(42)
                .accessCount(3)
                .lastAccessedAt(lastAccessed)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();

        assertEquals(AgentMemoryEntry.MemoryType.DEBUG_INSIGHT, entry.getType());
        assertEquals(AgentMemoryEntry.MemoryScope.GLOBAL, entry.getScope());
        assertEquals("/path/to/project", entry.getProject());
        assertEquals(0.85, entry.getConfidence());
        assertEquals(AgentMemoryEntry.MemorySource.EXPLICIT_HINT, entry.getSource());
        assertEquals(AgentMemoryEntry.MemoryStatus.PENDING, entry.getStatus());
        assertEquals(pendingExpiresAt, entry.getPendingExpiresAt());
        assertEquals(42, entry.getTokenCount());
        assertEquals(3, entry.getAccessCount());
        assertEquals(lastAccessed, entry.getLastAccessedAt());
        assertEquals(createdAt, entry.getCreatedAt());
        assertEquals(updatedAt, entry.getUpdatedAt());
    }

    @Test
    void toStringTruncatesLongContent() {
        String longContent = "a".repeat(200);
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-7")
                .content(longContent)
                .build();

        String str = entry.toString();
        assertTrue(str.contains("..."));
        assertTrue(str.length() < longContent.length() + 50);
    }

    @Test
    void keywordsAreImmutable() {
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id("test-8")
                .content("内容")
                .keywords(List.of("kw1", "kw2"))
                .build();

        List<String> keywords = entry.getKeywords();
        assertThrows(UnsupportedOperationException.class, () -> keywords.add("kw3"));
    }
}
