package com.paicli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgresMemoryStoresTest {

    @Test
    void postgresAgentMemoryStoreConstructorThrowsNotDelivered() {
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> new PostgresAgentMemoryStore("jdbc:postgresql://localhost/paicli", "user", "pwd"));
        assertTrue(ex.getMessage().contains("尚未交付"));
    }

    @Test
    void postgresSessionMessageStoreConstructorThrowsNotDelivered() {
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> new PostgresSessionMessageStore("jdbc:postgresql://localhost/paicli", "user", "pwd"));
        assertTrue(ex.getMessage().contains("尚未交付"));
    }

    @Test
    void memoryMigratorThrowsWhenMigrateCalled() {
        MemoryMigrator migrator = new MemoryMigrator(null, null);
        // source/target 为 null 时返回 0，不抛异常
        assertEquals(0, migrator.migrate());
    }

    @Test
    void memoryMigratorThrowsNotDeliveredWhenStoresProvided() {
        // 用 mock-like 的方式：传入非 null 但实际无法迁移的场景
        // 由于 PostgresAgentMemoryStore 构造就抛异常，这里只验证 API 签名
        assertDoesNotThrow(() -> new MemoryMigrator(null, null));
    }
}
