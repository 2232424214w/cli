package com.paicli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MemoryMaintenanceSchedulerTest {

    @Test
    void runMaintenanceNowCleansExpiredEntries(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/project/test", memoryDir, 100, 0.85)) {
            store.store(AgentMemoryEntry.builder().id("active")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.ACTIVE).build());
            store.store(AgentMemoryEntry.builder().id("expired")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.EXPIRED).build());
            store.store(AgentMemoryEntry.builder().id("pending-old")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.PENDING)
                    .pendingExpiresAt(Instant.parse("2020-01-01T00:00:00Z")).build());

            try (MemoryMaintenanceScheduler scheduler = new MemoryMaintenanceScheduler(store, 1)) {
                int deleted = scheduler.runMaintenanceNow();
                assertEquals(2, deleted, "应清理 expired + pending 超时共 2 条");
                assertEquals(1, store.size(), "应只剩 active 条目");
                assertTrue(store.retrieve("active").isPresent());
            }
        }
    }

    @Test
    void runMaintenanceNowReturnsZeroWhenNothingExpired(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/project/test", memoryDir, 100, 0.85)) {
            store.store(AgentMemoryEntry.builder().id("active1")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.ACTIVE).build());
            store.store(AgentMemoryEntry.builder().id("active2")
                    .content("内容").status(AgentMemoryEntry.MemoryStatus.ACTIVE).build());

            try (MemoryMaintenanceScheduler scheduler = new MemoryMaintenanceScheduler(store, 1)) {
                assertEquals(0, scheduler.runMaintenanceNow());
                assertEquals(2, store.size());
            }
        }
    }

    @Test
    void closeStopsScheduler(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/project/test", memoryDir, 100, 0.85)) {
            MemoryMaintenanceScheduler scheduler = new MemoryMaintenanceScheduler(store, 1);
            scheduler.close();
            // 再次 close 不应抛异常
            assertDoesNotThrow(() -> scheduler.close());
        }
    }

    @Test
    void runMaintenanceNowHandlesEmptyStore(@TempDir File memoryDir) throws Exception {
        try (SqliteAgentMemoryStore store = new SqliteAgentMemoryStore("/project/test", memoryDir, 100, 0.85)) {
            try (MemoryMaintenanceScheduler scheduler = new MemoryMaintenanceScheduler(store, 1)) {
                assertEquals(0, scheduler.runMaintenanceNow());
                assertEquals(0, store.size());
            }
        }
    }
}
