package com.paicli.memory;

/**
 * Agent 记忆库统计信息（供 /agent-memory stats 命令展示）。
 */
public record MemoryStats(
        int totalEntries,
        int activeEntries,
        int pendingEntries,
        int expiredEntries,
        int projectScopedEntries,
        int globalScopedEntries,
        int totalTokens,
        int totalAccessCount,
        double averageConfidence
) {
    public static MemoryStats empty() {
        return new MemoryStats(0, 0, 0, 0, 0, 0, 0, 0, 0.0);
    }

    public String formatForCli() {
        return String.format(
                "Agent 记忆统计:\n"
                        + "  总条目: %d (active=%d, pending=%d, expired=%d)\n"
                        + "  作用域: project=%d, global=%d\n"
                        + "  总 token: %d\n"
                        + "  总访问次数: %d\n"
                        + "  平均置信度: %.2f",
                totalEntries, activeEntries, pendingEntries, expiredEntries,
                projectScopedEntries, globalScopedEntries,
                totalTokens, totalAccessCount, averageConfidence
        );
    }
}
