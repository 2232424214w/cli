package com.bettercli.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {

    @Test
    void dualHitRanksAboveSingleListLeader() {
        // A 在两路都靠前，B 只在语义第一但关键词缺失 → A 的 RRF 应更高
        LinkedHashMap<String, Double> fused = ReciprocalRankFusion.fuse(List.of(
                List.of("B", "A", "C"),
                List.of("A", "D", "C")
        ), 60);
        List<String> order = List.copyOf(fused.keySet());
        assertEquals("A", order.get(0), "双路命中应排第一");
        assertTrue(fused.get("A") > fused.get("B"));
    }

    @Test
    void emptyListsYieldEmpty() {
        assertTrue(ReciprocalRankFusion.fuse(List.of(), 60).isEmpty());
        assertTrue(ReciprocalRankFusion.fuse(List.of(List.of()), 60).isEmpty());
    }
}
