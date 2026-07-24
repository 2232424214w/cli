package com.bettercli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionNotebookTest {

    @Test
    void appendUpdateSearchAndClear() {
        SessionNotebook nb = new SessionNotebook();
        var a = nb.append("目标", "完成 RAG 改造");
        var b = nb.append("约束", "不引入新依赖");
        assertEquals(2, nb.size());
        assertTrue(nb.formatView().contains("RAG"));

        nb.update(a.id(), "目标", "完成 RAG + 记事本");
        assertTrue(nb.search("记事本").stream().anyMatch(n -> n.id() == a.id()));
        assertEquals(1, nb.search("依赖").size());
        assertEquals(b.id(), nb.search("依赖").get(0).id());

        assertFalse(nb.formatSummary(10, 500).isBlank());
        nb.clear();
        assertTrue(nb.isEmpty());
        assertEquals("(记事本为空)", nb.formatView());
    }
}
