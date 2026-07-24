package com.bettercli.hitl;

import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AskUserToolTest {

    @Test
    void terminalAskUserReturnsFreeText() {
        Harness h = Harness.withInput("用 SQLite\n");
        String answer = h.handler.askUser(ClarificationRequest.of("用哪个数据库？"));
        assertEquals("用 SQLite", answer);
        assertTrue(h.output().contains("需要你的确认"));
    }

    @Test
    void terminalAskUserAcceptsOptionIndex() {
        Harness h = Harness.withInput("2\n");
        String answer = h.handler.askUser(new ClarificationRequest(
                "选存储？", List.of("JSON", "SQLite", "Postgres"), ""));
        assertEquals("SQLite", answer);
    }

    @Test
    void nonInteractiveDefaultUsesDefaultAnswer() {
        HitlHandler stub = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                return ApprovalResult.reject("n/a");
            }

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void setEnabled(boolean enabled) {
            }
        };
        String answer = stub.askUser(new ClarificationRequest("Q?", List.of(), "fallback-yes"));
        assertEquals("fallback-yes", answer);
    }

    @Test
    void toolRegistryAskUserViaHitl() {
        Harness h = Harness.withInput("继续用 Java\n");
        HitlToolRegistry registry = new HitlToolRegistry(h.handler);
        String result = registry.executeTool("ask_user",
                "{\"question\":\"项目语言？\",\"options\":\"Java\\nKotlin\"}");
        assertEquals("继续用 Java", result);
        assertTrue(registry.hasTool("ask_user"));
    }

    @Test
    void toolRegistryWithoutHitlUsesDefaultAnswer() {
        ToolRegistry registry = new ToolRegistry();
        String result = registry.executeTool("ask_user",
                "{\"question\":\"Q\",\"default_answer\":\"use-default\"}");
        assertEquals("use-default", result);
    }

    @Test
    void switchableDelegatesAskUser() {
        Harness h = Harness.withInput("ok\n");
        SwitchableHitlHandler switchable = new SwitchableHitlHandler(h.handler);
        assertEquals("ok", switchable.askUser(ClarificationRequest.of("go?")));
    }

    private static final class Harness {
        final TerminalHitlHandler handler;
        private final ByteArrayOutputStream sink;

        private Harness(String input) {
            this.sink = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
            this.handler = new TerminalHitlHandler(
                    true,
                    new BufferedReader(new StringReader(input)),
                    out
            );
        }

        static Harness withInput(String input) {
            return new Harness(input);
        }

        String output() {
            return sink.toString(StandardCharsets.UTF_8);
        }
    }
}
