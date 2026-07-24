package com.bettercli.memory;

import com.bettercli.rag.ReciprocalRankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从短期记忆和长期记忆中检索最相关的信息
 *
 * <p>长期记忆：关键词打分 +（可选）语义向量召回，经 {@link ReciprocalRankFusion} 融合；
 * FACT 类不做 24h 时间衰减（越稳越该保留）。
 */
public class MemoryRetriever {
    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private volatile MemoryVectorIndex vectorIndex;

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory) {
        this(shortTermMemory, longTermMemory, null);
    }

    public MemoryRetriever(ConversationMemory shortTermMemory, LongTermMemory longTermMemory,
                           MemoryVectorIndex vectorIndex) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.vectorIndex = vectorIndex;
    }

    public void setVectorIndex(MemoryVectorIndex vectorIndex) {
        this.vectorIndex = vectorIndex;
    }

    public MemoryVectorIndex getVectorIndex() {
        return vectorIndex;
    }

    /**
     * 检索与查询最相关的记忆
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        List<ScoredEntry> scored = new ArrayList<>();

        for (MemoryEntry entry : shortTermMemory.getAll()) {
            double score = computeRelevanceScore(entry, query);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score, true));
            }
        }

        List<MemoryEntry> longTermHits = retrieveLongTerm(query, Math.max(limit, 10), null);
        for (int i = 0; i < longTermHits.size(); i++) {
            // 次序越前分越高；与关键词分可比
            double score = 1.2 * (1.0 / (1 + i));
            scored.add(new ScoredEntry(longTermHits.get(i), score, false));
        }

        Map<String, ScoredEntry> best = new LinkedHashMap<>();
        for (ScoredEntry s : scored) {
            best.merge(s.entry().getId(), s, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return retrieveLongTerm(query, limit, null);
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        List<MemoryEntry> corpus = longTermMemory.getAll().stream()
                .filter(entry -> LongTermMemory.isVisibleInProject(entry, projectKey))
                .toList();
        if (corpus.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        Map<String, MemoryEntry> byId = new LinkedHashMap<>();
        for (MemoryEntry e : corpus) {
            byId.put(e.getId(), e);
        }

        List<String> keywordRanks = new ArrayList<>();
        Map<String, Double> keywordScores = new HashMap<>();
        for (MemoryEntry entry : corpus) {
            double score = computeRelevanceScore(entry, query) * 1.2;
            if (score > 0) {
                keywordScores.put(entry.getId(), score);
            }
        }
        keywordScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> keywordRanks.add(e.getKey()));

        List<String> semanticRanks = List.of();
        MemoryVectorIndex index = this.vectorIndex;
        if (index != null) {
            semanticRanks = index.search(query, corpus, Math.max(limit * 2, 10));
        }

        if (semanticRanks.isEmpty()) {
            return keywordRanks.stream()
                    .limit(limit)
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .toList();
        }

        LinkedHashMap<String, Double> fused = ReciprocalRankFusion.fuse(
                List.of(semanticRanks, keywordRanks), ReciprocalRankFusion.DEFAULT_K);

        List<MemoryEntry> out = new ArrayList<>();
        for (String id : fused.keySet()) {
            MemoryEntry entry = byId.get(id);
            if (entry != null) {
                out.add(entry);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        if (out.isEmpty() && !keywordRanks.isEmpty()) {
            log.debug("Semantic+RRF empty after fuse, falling back to keyword ranks");
            return keywordRanks.stream().limit(limit).map(byId::get).filter(Objects::nonNull).toList();
        }
        return out;
    }

    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, null);
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append("- [").append(entry.getType()).append("] ")
                    .append(entry.getContent()).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    /**
     * 计算记忆条目与查询的相关度分数（关键词路）。
     * FACT 不做对话式 24h 时间衰减。
     */
    double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        if (contentLower.contains(queryLower)) {
            return applyDecayIfNeeded(entry, 1.0);
        }

        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }

        if (matchedWords == 0) return 0;

        double keywordScore = (double) matchedWords / queryWords.size();
        return applyDecayIfNeeded(entry, keywordScore);
    }

    private static double applyDecayIfNeeded(MemoryEntry entry, double score) {
        if (entry.getType() == MemoryEntry.MemoryType.FACT) {
            return score;
        }
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0);
        return score * timeDecay;
    }

    private record ScoredEntry(MemoryEntry entry, double score, boolean fromShortTerm) {}
}
