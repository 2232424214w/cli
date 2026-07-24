package com.bettercli.rag;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码检索器：语义检索 + 图谱检索的统一入口
 */
public class CodeRetriever implements AutoCloseable {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 语义检索：用自然语言查询最相关的代码块
     */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    /**
     * 关键词检索：按类名/方法名/内容精确匹配
     */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    /**
     * 混合检索：语义列表 + 关键词列表做 RRF 融合（不再手调魔数权重），再按文件限流。
     */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        int semanticLimit = Math.max(topK * 2, 10);
        List<VectorStore.SearchResult> semantic = semanticSearch(query, semanticLimit);

        Map<String, VectorStore.SearchResult> byKey = new LinkedHashMap<>();
        List<String> semanticRanks = new ArrayList<>();
        for (VectorStore.SearchResult result : semantic) {
            String key = resultKey(result);
            byKey.putIfAbsent(key, result);
            semanticRanks.add(key);
        }

        List<String> keywordRanks = new ArrayList<>();
        Set<String> seenKeyword = new HashSet<>();
        for (String keyword : RagQueryTokenizer.tokenize(query)) {
            for (VectorStore.SearchResult result : keywordSearch(keyword)) {
                String key = resultKey(result);
                byKey.putIfAbsent(key, result);
                if (seenKeyword.add(key)) {
                    keywordRanks.add(key);
                }
            }
        }

        LinkedHashMap<String, Double> fused = ReciprocalRankFusion.fuse(
                List.of(semanticRanks, keywordRanks), ReciprocalRankFusion.DEFAULT_K);

        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> entry : fused.entrySet()) {
            VectorStore.SearchResult base = byKey.get(entry.getKey());
            if (base == null) {
                continue;
            }
            double typeBoost = switch (base.chunkType()) {
                case "method" -> 0.02;
                case "class" -> 0.01;
                default -> 0.0;
            };
            ranked.add(new VectorStore.SearchResult(
                    base.filePath(), base.chunkType(), base.name(), base.content(),
                    entry.getValue() + typeBoost));
        }
        return limitPerFile(ranked, topK, 2);
    }

    private static String resultKey(VectorStore.SearchResult result) {
        return result.filePath() + "#" + result.name();
    }

    /** 同一文件最多保留 maxPerFile 个结果，总数不超过 topK */
    private List<VectorStore.SearchResult> limitPerFile(List<VectorStore.SearchResult> sorted, int topK, int maxPerFile) {
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (VectorStore.SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 图谱检索：查询指定类/方法的关系图谱
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计
     */
    public VectorStore.IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
