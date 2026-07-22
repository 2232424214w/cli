package com.bettercli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆后端数据迁移工具（sqlite → postgres，云端切换用）。
 *
 * <p>当前状态：<b>骨架预留，尚未交付</b>。需要 PostgreSQL JDBC 驱动就绪后才能实现。
 *
 * <p>交付时用法：
 * <pre>{@code
 * bettercli memory migrate --from sqlite --to postgres \
 *     --postgres-url=jdbc:postgresql://host:5432/bettercli \
 *     --postgres-user=bettercli --postgres-password=***
 * }</pre>
 *
 * <p>迁移范围：
 * <ul>
 *   <li>{@code agent_memory_entries}：Agent 维护的事实记忆</li>
 *   <li>{@code session_messages}：历史会话消息</li>
 *   <li>{@code user_vocabulary}：用户词汇表</li>
 * </ul>
 *
 * <p>设计参考：docs/memory-system-design.md §10.4
 */
public class MemoryMigrator {
    private static final Logger log = LoggerFactory.getLogger(MemoryMigrator.class);

    private final AgentMemoryStore source;
    private final AgentMemoryStore target;

    public MemoryMigrator(AgentMemoryStore source, AgentMemoryStore target) {
        this.source = source;
        this.target = target;
    }

    /**
     * 执行迁移。返回迁移条目数。
     * 当前为骨架，交付时实现完整迁移逻辑。
     */
    public int migrate() {
        if (source == null || target == null) {
            log.warn("迁移源或目标未初始化");
            return 0;
        }
        throw new UnsupportedOperationException(
                "MemoryMigrator 尚未交付：需要 PostgreSQL 后端就绪后实现。"
                        + "当前请继续使用 SQLite（BETTERCLI_MEMORY_BACKEND=sqlite）");
    }
}
