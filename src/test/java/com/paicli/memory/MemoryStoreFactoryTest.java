package com.paicli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToSqliteBackend() {
        MemoryStoreFactory factory = new MemoryStoreFactory(tempDir.toString(), tempDir.toFile());
        assertEquals("sqlite", factory.getBackend());
    }

    @Test
    void createAgentMemoryStoreReturnsSqliteImplementation() throws Exception {
        MemoryStoreFactory factory = new MemoryStoreFactory(tempDir.toString(), tempDir.toFile());
        try (AgentMemoryStore store = factory.createAgentMemoryStore()) {
            assertNotNull(store);
            assertTrue(store instanceof SqliteAgentMemoryStore);
        }
    }

    @Test
    void createSessionMessageStoreReturnsSqliteImplementation() {
        MemoryStoreFactory factory = new MemoryStoreFactory(tempDir.toString(), tempDir.toFile());
        try (SessionMessageStore store = factory.createSessionMessageStore()) {
            assertNotNull(store);
            assertTrue(store instanceof SqliteSessionMessageStore);
        }
    }

    @Test
    void postgresBackendFallsBackToSqlite() throws Exception {
        System.setProperty("paicli.memory.backend", "postgres");
        try {
            MemoryStoreFactory factory = new MemoryStoreFactory(tempDir.toString(), tempDir.toFile());
            assertEquals("postgres", factory.getBackend());
            try (AgentMemoryStore store = factory.createAgentMemoryStore()) {
                // postgres 未交付，应回退到 sqlite
                assertNotNull(store);
                assertTrue(store instanceof SqliteAgentMemoryStore);
            }
            try (SessionMessageStore store = factory.createSessionMessageStore()) {
                assertNotNull(store);
                assertTrue(store instanceof SqliteSessionMessageStore);
            }
        } finally {
            System.clearProperty("paicli.memory.backend");
        }
    }

    @Test
    void unknownBackendFallsBackToSqlite() throws Exception {
        System.setProperty("paicli.memory.backend", "unknown-db");
        try {
            MemoryStoreFactory factory = new MemoryStoreFactory(tempDir.toString(), tempDir.toFile());
            assertEquals("unknown-db", factory.getBackend());
            try (AgentMemoryStore store = factory.createAgentMemoryStore()) {
                assertNotNull(store);
                assertTrue(store instanceof SqliteAgentMemoryStore);
            }
        } finally {
            System.clearProperty("paicli.memory.backend");
        }
    }

    @Test
    void currentBackendReflectsSystemProperty() {
        System.setProperty("paicli.memory.backend", "postgres");
        try {
            assertEquals("postgres", MemoryStoreFactory.currentBackend());
        } finally {
            System.clearProperty("paicli.memory.backend");
        }
    }

    @Test
    void currentBackendFallsBackToEnv() {
        // 环境变量在测试中难以设置，仅验证默认值
        System.clearProperty("paicli.memory.backend");
        assertEquals("sqlite", MemoryStoreFactory.currentBackend());
    }

    @Test
    void factoryCreatedStoresAreFunctional() throws Exception {
        MemoryStoreFactory factory = new MemoryStoreFactory(tempDir.toString(), tempDir.toFile());
        try (AgentMemoryStore store = factory.createAgentMemoryStore()) {
            store.store(AgentMemoryEntry.builder()
                    .id("test-1")
                    .content("测试 SQLite 工厂创建")
                    .keywords(java.util.List.of("SQLite", "工厂"))
                    .confidence(0.8)
                    .project(tempDir.toString())
                    .build());
            assertEquals(1, store.size());
        }
    }
}
