package com.bettercli.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalOverlapRerankerTest {

    @Test
    void prefersNameMatchOverWeakContent() {
        VectorStore.SearchResult weak = new VectorStore.SearchResult(
                "a.java", "method", "Other.helper()", "unrelated body", 0.9);
        VectorStore.SearchResult strong = new VectorStore.SearchResult(
                "b.java", "method", "Agent.run(String)", "ReAct loop body", 0.1);
        Set<String> tokens = LexicalOverlapReranker.extractTokens("Agent ReAct");
        assertTrue(tokens.contains("agent"), () -> "tokens=" + tokens);
        assertTrue(tokens.contains("react"), () -> "tokens=" + tokens);
        List<VectorStore.SearchResult> out = LexicalOverlapReranker.rerank(
                "Agent ReAct", List.of(weak, strong), 2);
        assertEquals("Agent.run(String)", out.get(0).name(), () -> "out=" + out + " tokens=" + tokens);
        assertTrue(out.get(0).similarity() > out.get(1).similarity());
    }

    @Test
    void emptyQueryTokensKeepsPriorOrder() {
        VectorStore.SearchResult a = new VectorStore.SearchResult("a.java", "method", "A.a()", "x", 1.0);
        VectorStore.SearchResult b = new VectorStore.SearchResult("b.java", "method", "B.b()", "y", 0.5);
        List<VectorStore.SearchResult> out = LexicalOverlapReranker.rerank("   ", List.of(a, b), 2);
        assertEquals("A.a()", out.get(0).name());
    }

    @Test
    void overlapScoreWeightsNameHigher() {
        Set<String> tokens = Set.of("agent");
        VectorStore.SearchResult inName = new VectorStore.SearchResult(
                "a.java", "method", "Agent.run()", "no keyword here", 0);
        VectorStore.SearchResult inContent = new VectorStore.SearchResult(
                "b.java", "method", "Other.run()", "calls Agent somehow", 0);
        assertTrue(LexicalOverlapReranker.overlapScore(tokens, inName)
                > LexicalOverlapReranker.overlapScore(tokens, inContent));
    }

    @Test
    void extractTokensFindsAsciiIdentifiers() {
        Set<String> tokens = LexicalOverlapReranker.extractTokens("Agent的ReAct循环");
        assertTrue(tokens.contains("agent"));
        assertTrue(tokens.contains("react"));
    }
}
