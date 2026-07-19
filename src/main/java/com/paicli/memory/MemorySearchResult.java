package com.paicli.memory;

/**
 * Agent 记忆检索结果（带 BM25 分数和 confidence 加权后的最终得分）。
 */
public record MemorySearchResult(
        AgentMemoryEntry entry,
        double bm25Score,
        double confidenceWeight,
        double finalScore
) {
    public MemorySearchResult(AgentMemoryEntry entry, double bm25Score, double confidenceWeight) {
        this(entry, bm25Score, confidenceWeight, bm25Score * confidenceWeight);
    }

    public String formatForTool() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(entry.getType()).append("] ");
        sb.append("id=").append(entry.getId());
        sb.append(" (score=").append(String.format("%.3f", finalScore));
        sb.append(", bm25=").append(String.format("%.3f", bm25Score));
        sb.append(", conf=").append(String.format("%.2f", entry.getConfidence()));
        sb.append(")\n");
        sb.append("  content: ").append(entry.getContent());
        if (!entry.getKeywords().isEmpty()) {
            sb.append("\n  keywords: ").append(String.join(", ", entry.getKeywords()));
        }
        sb.append("\n  scope: ").append(entry.getScope());
        if (entry.getProject() != null) {
            sb.append(" (").append(entry.getProject()).append(")");
        }
        return sb.toString();
    }
}
