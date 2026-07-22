package com.bettercli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;

/**
 * 记忆存储后端工厂（可插拔后端架构，对标美团 1024 Agent 的本地/云端切换）。
 *
 * 配置驱动：
 * - `bettercli.memory.backend` 系统属性 或 `BETTERCLI_MEMORY_BACKEND` 环境变量
 * - 取值：`sqlite`（默认，本地）/ `postgres`（云端，需要 PostgreSQL JDBC 驱动）
 *
 * 当前已交付：`sqlite`（SqliteAgentMemoryStore + SqliteSessionMessageStore）
 * 预留扩展：`postgres`（PostgresAgentMemoryStore + PostgresSessionMessageStore，云端场景启用）
 *
 * 设计参考：docs/memory-system-design.md §4.1 / §10.4
 */
public class MemoryStoreFactory {
    private static final Logger log = LoggerFactory.getLogger(MemoryStoreFactory.class);

    private static final String BACKEND_PROPERTY = "bettercli.memory.backend";
    private static final String BACKEND_ENV = "BETTERCLI_MEMORY_BACKEND";
    private static final String DEFAULT_BACKEND = "sqlite";

    private final String backend;
    private final File memoryDir;
    private final String projectPath;

    public MemoryStoreFactory(String projectPath, File memoryDir) {
        this.projectPath = projectPath;
        this.memoryDir = memoryDir;
        this.backend = resolveBackend();
    }

    private static String resolveBackend() {
        String configured = System.getProperty(BACKEND_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(BACKEND_ENV);
        }
        return configured == null || configured.isBlank() ? DEFAULT_BACKEND : configured.trim().toLowerCase();
    }

    public String getBackend() {
        return backend;
    }

    /**
     * 创建 Agent 记忆存储。sqlite 返回 SQLite 实现；postgres 暂未实现，返回 null 并记录警告。
     */
    public AgentMemoryStore createAgentMemoryStore() {
        if ("sqlite".equals(backend)) {
            try {
                return new SqliteAgentMemoryStore(projectPath, memoryDir, 1000, 0.85);
            } catch (Exception e) {
                log.warn("创建 SqliteAgentMemoryStore 失败: {}", e.getMessage());
                return null;
            }
        }
        if ("postgres".equals(backend)) {
            log.warn("PostgreSQL Agent 记忆后端尚未交付（需要 PostgreSQL JDBC 驱动），回退到 sqlite");
            try {
                return new SqliteAgentMemoryStore(projectPath, memoryDir, 1000, 0.85);
            } catch (Exception e) {
                log.warn("回退 SqliteAgentMemoryStore 失败: {}", e.getMessage());
                return null;
            }
        }
        log.warn("未知记忆后端 backend={}, 回退到 sqlite", backend);
        try {
            return new SqliteAgentMemoryStore(projectPath, memoryDir, 1000, 0.85);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 创建会话消息存储。sqlite 返回 SQLite 实现；postgres 暂未实现，回退到 sqlite。
     */
    public SessionMessageStore createSessionMessageStore() {
        if ("sqlite".equals(backend)) {
            try {
                return new SqliteSessionMessageStore(memoryDir);
            } catch (Exception e) {
                log.warn("创建 SqliteSessionMessageStore 失败: {}", e.getMessage());
                return null;
            }
        }
        if ("postgres".equals(backend)) {
            log.warn("PostgreSQL 会话消息后端尚未交付（需要 PostgreSQL JDBC 驱动），回退到 sqlite");
            try {
                return new SqliteSessionMessageStore(memoryDir);
            } catch (Exception e) {
                log.warn("回退 SqliteSessionMessageStore 失败: {}", e.getMessage());
                return null;
            }
        }
        log.warn("未知记忆后端 backend={}, 回退到 sqlite", backend);
        try {
            return new SqliteSessionMessageStore(memoryDir);
        } catch (Exception e) {
            return null;
        }
    }

    public static String currentBackend() {
        return resolveBackend();
    }
}
