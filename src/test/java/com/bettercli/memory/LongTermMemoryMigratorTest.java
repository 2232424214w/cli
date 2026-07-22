package com.bettercli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryMigratorTest {

    @TempDir
    Path tempDir;

    private com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private List<Map<String, Object>> entryMap(String id, String content, String type,
                                                String scope, String project, String timestamp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("content", content);
        map.put("type", type);
        map.put("timestamp", timestamp);
        Map<String, String> metadata = new LinkedHashMap<>();
        if (scope != null) metadata.put("scope", scope);
        if (project != null) metadata.put("project", project);
        map.put("metadata", metadata);
        map.put("tokenCount", 10);
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(map);
        return list;
    }

    private File writeJsonFile(String filename, List<Map<String, Object>> data) throws Exception {
        File file = tempDir.resolve(filename).toFile();
        mapper.writeValue(file, data);
        return file;
    }

    @Test
    void migrateReturnsZeroWhenSourceFileMissing() throws Exception {
        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            assertEquals(0, migrator.migrate());
        }
    }

    @Test
    void migrateImportsEntriesFromJson() throws Exception {
        File jsonFile = writeJsonFile("long_term_memory.json", entryMap(
                "fact-001", "项目使用 SQLite 作为本地存储", "FACT",
                "project", "/home/user/proj", "2024-01-01T00:00:00Z"));

        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            int migrated = migrator.migrate();
            assertEquals(1, migrated);
            assertEquals(1, store.size());

            List<AgentMemoryEntry> entries = store.list(MemoryListQuery.builder().limit(10).build());
            AgentMemoryEntry entry = entries.get(0);
            assertEquals("项目使用 SQLite 作为本地存储", entry.getContent());
            assertEquals(AgentMemoryEntry.MemorySource.MIGRATED, entry.getSource());
            assertEquals(AgentMemoryEntry.MemoryScope.PROJECT, entry.getScope());
            assertEquals("/home/user/proj", entry.getProject());
            assertEquals(AgentMemoryEntry.MemoryStatus.ACTIVE, entry.getStatus());
            assertTrue(entry.getConfidence() >= 0.79 && entry.getConfidence() <= 0.81);
            assertTrue(entry.getId().startsWith("migrated-fact-001"));
        }
    }

    @Test
    void migrateIsIdempotent() throws Exception {
        writeJsonFile("long_term_memory.json", entryMap(
                "fact-001", "项目使用 SQLite", "FACT",
                "project", "/proj", "2024-01-01T00:00:00Z"));

        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            int first = migrator.migrate();
            int second = migrator.migrate();
            assertEquals(1, first);
            assertEquals(0, second);
            assertEquals(1, store.size());
        }
    }

    @Test
    void migrateWritesMarkerFileAndSkipsNextRun() throws Exception {
        writeJsonFile("long_term_memory.json", entryMap(
                "fact-001", "项目使用 SQLite", "FACT",
                "project", "/proj", "2024-01-01T00:00:00Z"));

        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            assertEquals(1, migrator.migrate());
            assertTrue(tempDir.resolve(".migrated-to-sqlite").toFile().exists());
            assertEquals(0, migrator.migrate());
        }
    }

    @Test
    void migratePreservesOriginalJsonFile() throws Exception {
        File jsonFile = writeJsonFile("long_term_memory.json", entryMap(
                "fact-001", "项目使用 SQLite", "FACT",
                "project", "/proj", "2024-01-01T00:00:00Z"));

        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            migrator.migrate();
            assertTrue(jsonFile.exists());
        }
    }

    @Test
    void migrateHandlesGlobalScope() throws Exception {
        writeJsonFile("long_term_memory.json", entryMap(
                "fact-002", "默认用中文回答", "FACT",
                "global", null, "2024-01-01T00:00:00Z"));

        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            assertEquals(1, migrator.migrate());
            List<AgentMemoryEntry> entries = store.list(MemoryListQuery.builder().limit(10).build());
            assertEquals(AgentMemoryEntry.MemoryScope.GLOBAL, entries.get(0).getScope());
            assertNull(entries.get(0).getProject());
        }
    }

    @Test
    void migrateSkipsBlankContent() throws Exception {
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("id", "fact-blank");
        blank.put("content", "");
        blank.put("type", "FACT");
        blank.put("timestamp", "2024-01-01T00:00:00Z");
        blank.put("metadata", new LinkedHashMap<>());
        blank.put("tokenCount", 0);
        data.add(blank);
        writeJsonFile("long_term_memory.json", data);

        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 1000, 0.85)) {
            LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(store, tempDir.toFile());
            assertEquals(0, migrator.migrate());
            assertEquals(0, store.size());
        }
    }

    @Test
    void migrateWithNullStoreReturnsZero() throws Exception {
        writeJsonFile("long_term_memory.json", entryMap(
                "fact-001", "test", "FACT", "project", "/p", "2024-01-01T00:00:00Z"));
        LongTermMemoryMigrator migrator = new LongTermMemoryMigrator(null, tempDir.toFile());
        assertEquals(0, migrator.migrate());
    }
}
