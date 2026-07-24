package com.bettercli.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 lexical overlap rerank：对 RRF 召回的候选按查询词与 name/content 的重叠度重排。
 *
 * <p>不引入 cross-encoder / 额外 LLM 调用，零外部依赖；后续可换成可插拔实现。
 * 分词故意用简单 ASCII/空白规则，避免依赖 jieba 结果波动。
 */
public final class LexicalOverlapReranker {

    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.$\\-]{1,}");

    private LexicalOverlapReranker() {
    }

    /**
     * @param query      原始查询
     * @param candidates RRF（或其它召回）结果，顺序作为次级排序键
     * @param topK       返回条数上限（≤0 表示全部）
     */
    public static List<VectorStore.SearchResult> rerank(
            String query, List<VectorStore.SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> queryTokens = extractTokens(query);
        if (queryTokens.isEmpty()) {
            int limit = topK <= 0 ? candidates.size() : Math.min(topK, candidates.size());
            return new ArrayList<>(candidates.subList(0, limit));
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            VectorStore.SearchResult c = candidates.get(i);
            double overlap = overlapScore(queryTokens, c);
            scored.add(new Scored(c, overlap, i, c.similarity()));
        }
        scored.sort(Comparator
                .comparingDouble(Scored::overlap).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::priorSimilarity).reversed())
                .thenComparingInt(Scored::priorRank));

        int limit = topK <= 0 ? scored.size() : Math.min(topK, scored.size());
        List<VectorStore.SearchResult> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Scored s = scored.get(i);
            out.add(new VectorStore.SearchResult(
                    s.result.filePath(),
                    s.result.chunkType(),
                    s.result.name(),
                    s.result.content(),
                    s.overlap));
        }
        return out;
    }

    static Set<String> extractTokens(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        Matcher matcher = ASCII_TOKEN.matcher(query);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        // 连续汉字（≥2）也纳入，便于中文查询词命中 content
        StringBuilder han = new StringBuilder();
        query.codePoints().forEach(cp -> {
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                han.appendCodePoint(cp);
            } else {
                flushHan(han, tokens);
            }
        });
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushHan(StringBuilder han, Set<String> tokens) {
        if (han.length() >= 2) {
            tokens.add(han.toString());
        }
        han.setLength(0);
    }

    static double overlapScore(Set<String> queryTokens, VectorStore.SearchResult result) {
        if (queryTokens == null || queryTokens.isEmpty() || result == null) {
            return 0.0;
        }
        String name = result.name() == null ? "" : result.name().toLowerCase(Locale.ROOT);
        String content = result.content() == null ? "" : result.content().toLowerCase(Locale.ROOT);
        double hit = 0.0;
        for (String token : queryTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (name.contains(token)) {
                hit += 2.0;
            } else if (content.contains(token)) {
                hit += 1.0;
            }
        }
        return hit / (queryTokens.size() * 2.0);
    }

    private record Scored(VectorStore.SearchResult result, double overlap, int priorRank, double priorSimilarity) {
    }
}
