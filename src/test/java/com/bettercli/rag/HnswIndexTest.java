package com.bettercli.rag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HnswIndexTest {

    @Test
    void exactPathReturnsNearestNeighbor() {
        HnswIndex index = new HnswIndex();
        index.addAll(List.of(
                new HnswIndex.Item("a.java", "method", "A", "a", new float[]{1f, 0f, 0f}),
                new HnswIndex.Item("b.java", "method", "B", "b", new float[]{0f, 1f, 0f}),
                new HnswIndex.Item("c.java", "method", "C", "c", new float[]{0.9f, 0.1f, 0f})
        ));
        List<VectorStore.SearchResult> results = index.search(new float[]{1f, 0f, 0f}, 2);
        assertEquals("A", results.get(0).name());
        assertTrue(results.get(0).similarity() > 0.99);
    }

    @Test
    void largeIndexApproxStillFindsNearDuplicate() {
        HnswIndex index = new HnswIndex();
        List<HnswIndex.Item> batch = new ArrayList<>();
        // 超过 EXACT_THRESHOLD；围绕 query 方向撒点，保证 NSW 图连通
        for (int i = 0; i < HnswIndex.EXACT_THRESHOLD + 40; i++) {
            float[] emb = new float[8];
            emb[0] = 0.7f + (i % 10) * 0.01f;
            emb[1] = 0.2f;
            emb[2] = (i % 5) * 0.05f;
            batch.add(new HnswIndex.Item("f" + i + ".java", "method", "M" + i, "body", emb));
        }
        batch.add(new HnswIndex.Item("target.java", "method", "TARGET", "hit",
                new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}));
        index.addAll(batch);

        List<VectorStore.SearchResult> results = index.search(
                new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}, 5);
        assertEquals("TARGET", results.get(0).name(),
                () -> "results=" + results.stream().map(VectorStore.SearchResult::name).toList());
    }
}
