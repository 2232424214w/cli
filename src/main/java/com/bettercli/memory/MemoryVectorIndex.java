package com.bettercli.memory;

import com.bettercli.rag.EmbeddingClient;
import com.bettercli.rag.HnswIndex;
import com.bettercli.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆向量索引：复用 {@link EmbeddingClient} + {@link HnswIndex}，
 * 供 {@link MemoryRetriever} 做语义召回；embedding 失败时调用方回退关键词。
 */
public class MemoryVectorIndex {
    private static final Logger log = LoggerFactory.getLogger(MemoryVectorIndex.class);

    @FunctionalInterface
    public interface Embedder {
        float[] embed(String text) throws Exception;
    }

    private final Embedder embedder;
    private final Map<String, float[]> cached = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private HnswIndex index = new HnswIndex();
    private Set<String> indexedIds = Set.of();

    public MemoryVectorIndex(Embedder embedder) {
        this.embedder = embedder;
    }

    public MemoryVectorIndex(EmbeddingClient client) {
        this(client::embed);
    }

    public void invalidate(String id) {
        if (id != null) {
            cached.remove(id);
        }
        synchronized (lock) {
            indexedIds = Set.of();
            index = new HnswIndex();
        }
    }

    public void clear() {
        cached.clear();
        synchronized (lock) {
            indexedIds = Set.of();
            index = new HnswIndex();
        }
    }

    /**
     * @return 按语义相似度降序的 memory entry id；失败返回空列表
     */
    public List<String> search(String query, List<MemoryEntry> corpus, int topK) {
        if (query == null || query.isBlank() || corpus == null || corpus.isEmpty() || topK <= 0) {
            return List.of();
        }
        try {
            ensureIndexed(corpus);
            float[] q = embedder.embed(query);
            if (q == null || q.length == 0) {
                return List.of();
            }
            List<VectorStore.SearchResult> hits;
            synchronized (lock) {
                hits = index.search(q, topK);
            }
            List<String> ids = new ArrayList<>(hits.size());
            for (VectorStore.SearchResult hit : hits) {
                if (hit.name() != null && !hit.name().isBlank()) {
                    ids.add(hit.name());
                }
            }
            return ids;
        } catch (Exception e) {
            log.debug("Memory semantic search failed, falling back to keyword: {}", e.toString());
            return List.of();
        }
    }

    private void ensureIndexed(List<MemoryEntry> corpus) throws Exception {
        Set<String> wanted = new HashSet<>();
        for (MemoryEntry e : corpus) {
            if (e != null && e.getId() != null) {
                wanted.add(e.getId());
            }
        }
        synchronized (lock) {
            if (wanted.equals(indexedIds) && index.size() == wanted.size()) {
                return;
            }
            List<HnswIndex.Item> batch = new ArrayList<>();
            for (MemoryEntry entry : corpus) {
                if (entry == null || entry.getId() == null || entry.getContent() == null) {
                    continue;
                }
                float[] vec = cached.get(entry.getId());
                if (vec == null) {
                    vec = embedder.embed(entry.getContent());
                    if (vec == null || vec.length == 0) {
                        continue;
                    }
                    cached.put(entry.getId(), vec);
                }
                batch.add(new HnswIndex.Item(
                        entry.getId(), "fact", entry.getId(), entry.getContent(), vec));
            }
            index = new HnswIndex();
            index.addAll(batch);
            indexedIds = Set.copyOf(wanted);
        }
    }
}
