package com.bettercli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemTerminalFactoryTest {

    @Test
    void forceWindowsVtMatchesTermEnvSemantics() {
        String term = System.getenv("TERM");
        boolean expectForce = term == null
                || term.isBlank()
                || "dumb".equalsIgnoreCase(term)
                || term.toLowerCase().startsWith("dumb-color");
        assertEquals(expectForce, SystemTerminalFactory.shouldForceWindowsVtType());
    }
}
