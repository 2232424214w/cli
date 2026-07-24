package com.bettercli.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：把多路排序列表融合成统一分数，避免手调魔数权重。
 *
 * <p>公式：{@code score(d) = Σ 1 / (k + rank_i(d))}，rank 从 1 开始。
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    /**
     * @param rankedLists 每路已按相关性降序的文档 key 列表（允许重复 key 出现在多路）
     * @param k           RRF 平滑常数，常用 60
     * @return key → RRF 分数，按分数降序的 LinkedHashMap
     */
    public static LinkedHashMap<String, Double> fuse(List<List<String>> rankedLists, int k) {
        Map<String, Double> scores = new HashMap<>();
        int smooth = Math.max(1, k);
        if (rankedLists != null) {
            for (List<String> list : rankedLists) {
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    String key = list.get(i);
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    int rank = i + 1;
                    scores.merge(key, 1.0 / (smooth + rank), Double::sum);
                }
            }
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        LinkedHashMap<String, Double> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : sorted) {
            ordered.put(e.getKey(), e.getValue());
        }
        return ordered;
    }
}
