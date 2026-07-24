package com.bettercli.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 轻量单层 NSW / HNSW 风格近似最近邻索引（纯 Java，零外部依赖）。
 *
 * <p>小规模（≤ {@link #EXACT_THRESHOLD}）走精确暴力，保证与余弦全表扫描一致；
 * 更大规模走可导航小世界图近似检索，避免 O(n) 全表扫描退化。
 */
public final class HnswIndex {

    public static final int EXACT_THRESHOLD = 64;
    private static final int M = 12;
    private static final int EF_SEARCH = 32;

    public record Item(String filePath, String chunkType, String name, String content, float[] embedding) {
    }

    private final List<Item> items = new ArrayList<>();
    private final List<int[]> neighbors = new ArrayList<>();
    private int entryPoint = -1;
    private boolean graphBuilt;

    public synchronized void clear() {
        items.clear();
        neighbors.clear();
        entryPoint = -1;
        graphBuilt = false;
    }

    public synchronized void addAll(List<Item> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (Item item : batch) {
            if (item == null || item.embedding() == null || item.embedding().length == 0) {
                continue;
            }
            items.add(item);
            neighbors.add(new int[0]);
        }
        graphBuilt = false;
        if (!items.isEmpty()) {
            entryPoint = items.size() - 1;
        }
    }

    public synchronized int size() {
        return items.size();
    }

    /**
     * @return 按余弦相似度降序的 TopK（similarity 字段写入 SearchResult）
     */
    public synchronized List<VectorStore.SearchResult> search(float[] query, int topK) {
        if (query == null || query.length == 0 || items.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (items.size() <= EXACT_THRESHOLD) {
            return exactSearch(query, topK);
        }
        ensureGraph();
        return approxSearch(query, topK);
    }

    private List<VectorStore.SearchResult> exactSearch(float[] query, int topK) {
        List<Scored> scored = new ArrayList<>(items.size());
        for (Item item : items) {
            scored.add(new Scored(item, cosine(query, item.embedding())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toResults(scored, topK);
    }

    private void ensureGraph() {
        if (graphBuilt || items.size() <= 1) {
            graphBuilt = true;
            return;
        }
        List<List<Integer>> undirected = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            undirected.add(new ArrayList<>());
        }
        for (int i = 0; i < items.size(); i++) {
            for (int nb : selectNeighbors(i)) {
                if (!undirected.get(i).contains(nb)) {
                    undirected.get(i).add(nb);
                }
                if (!undirected.get(nb).contains(i)) {
                    undirected.get(nb).add(i);
                }
            }
        }
        for (int i = 0; i < items.size(); i++) {
            List<Integer> links = undirected.get(i);
            int[] arr = new int[links.size()];
            for (int j = 0; j < links.size(); j++) {
                arr[j] = links.get(j);
            }
            neighbors.set(i, arr);
        }
        entryPoint = items.size() / 2;
        graphBuilt = true;
    }

    private int[] selectNeighbors(int node) {
        List<ScoredIdx> candidates = new ArrayList<>(items.size());
        float[] vec = items.get(node).embedding();
        for (int j = 0; j < items.size(); j++) {
            if (j == node) {
                continue;
            }
            candidates.add(new ScoredIdx(j, cosine(vec, items.get(j).embedding())));
        }
        candidates.sort(Comparator.comparingDouble(ScoredIdx::score).reversed());
        int take = Math.min(M, candidates.size());
        int[] links = new int[take];
        for (int i = 0; i < take; i++) {
            links[i] = candidates.get(i).idx();
        }
        return links;
    }

    private List<VectorStore.SearchResult> approxSearch(float[] query, int topK) {
        int ef = Math.max(EF_SEARCH, topK * 2);
        PriorityQueue<ScoredIdx> w = new PriorityQueue<>(Comparator.comparingDouble(ScoredIdx::score));
        boolean[] visited = new boolean[items.size()];

        // 多入口：entry + 若干随机点，降低 NSW 丢召回
        List<Integer> seeds = new ArrayList<>();
        seeds.add(entryPoint >= 0 ? entryPoint : 0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 4 && items.size() > 1; i++) {
            seeds.add(rnd.nextInt(items.size()));
        }

        PriorityQueue<ScoredIdx> candidates = new PriorityQueue<>(Comparator.comparingDouble(ScoredIdx::score).reversed());
        for (int seed : seeds) {
            if (visited[seed]) {
                continue;
            }
            double score = cosine(query, items.get(seed).embedding());
            candidates.add(new ScoredIdx(seed, score));
            w.add(new ScoredIdx(seed, score));
            visited[seed] = true;
            if (w.size() > ef) {
                w.poll();
            }
        }

        while (!candidates.isEmpty()) {
            ScoredIdx current = candidates.poll();
            ScoredIdx worst = w.peek();
            if (worst != null && current.score() < worst.score() && w.size() >= ef) {
                break;
            }
            for (int nb : neighbors.get(current.idx())) {
                if (visited[nb]) {
                    continue;
                }
                visited[nb] = true;
                double score = cosine(query, items.get(nb).embedding());
                if (w.size() < ef || score > w.peek().score()) {
                    candidates.add(new ScoredIdx(nb, score));
                    w.add(new ScoredIdx(nb, score));
                    if (w.size() > ef) {
                        w.poll();
                    }
                }
            }
        }

        List<Scored> scored = new ArrayList<>(w.size());
        for (ScoredIdx s : w) {
            scored.add(new Scored(items.get(s.idx()), s.score()));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toResults(scored, topK);
    }

    private static List<VectorStore.SearchResult> toResults(List<Scored> scored, int topK) {
        int limit = Math.min(topK, scored.size());
        List<VectorStore.SearchResult> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Scored s = scored.get(i);
            Item item = s.item();
            out.add(new VectorStore.SearchResult(
                    item.filePath(), item.chunkType(), item.name(), item.content(), s.score()));
        }
        return out;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Scored(Item item, double score) {
    }

    private record ScoredIdx(int idx, double score) {
    }
}
