package com.bettercli.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCardTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void rejectsBlankNameOrUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCard("", "desc", "http://x", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentCard("n", "desc", "  ", List.of()));
    }

    @Test
    void nullSkillsBecomesEmpty() {
        AgentCard card = new AgentCard("n", "d", "http://x", null);
        assertTrue(card.skills().isEmpty());
    }

    @Test
    void hasSkillMatchesCaseInsensitive() {
        AgentCard card = new AgentCard("n", "d", "http://x", List.of("Code-Review", "Security"));
        assertTrue(card.hasSkill("code-review"));
        assertTrue(card.hasSkill("SECURITY"));
        assertFalse(card.hasSkill("unknown"));
        assertFalse(card.hasSkill(""));
    }

    @Test
    void skillsIsImmutable() {
        AgentCard card = new AgentCard("n", "d", "http://x", List.of("a"));
        assertThrows(UnsupportedOperationException.class, () -> card.skills().add("b"));
    }
}
