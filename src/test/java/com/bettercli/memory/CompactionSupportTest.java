package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactionSupportTest {

    @Test
    void overflowTriggerDetectsBothApiFallbacks() {
        assertEquals(CompactTrigger.PROMPT_TOO_LONG,
                CompactionSupport.overflowTrigger(new IOException("prompt is too long")));
        assertEquals(CompactTrigger.CONTEXT_WINDOW_EXCEEDED,
                CompactionSupport.overflowTrigger(new IOException("model_context_window_exceeded")));
        assertNull(CompactionSupport.overflowTrigger(new IOException("timeout")));
        assertNull(CompactionSupport.overflowTrigger(null));
    }

    @Test
    void findLastUserIndexSkipsTrailingAssistant() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("s"));
        history.add(LlmClient.Message.user("u1"));
        history.add(LlmClient.Message.assistant("a1"));
        history.add(LlmClient.Message.user("u2"));
        history.add(LlmClient.Message.assistant("a2"));
        assertEquals(3, CompactionSupport.findLastUserIndex(history));
    }

    @Test
    void findLastUserIndexSkipsInjectedSystemishUserMessages() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.user("real"));
        history.add(LlmClient.Message.user("[反思提示] ignore"));
        history.add(LlmClient.Message.assistant("a"));
        assertEquals(0, CompactionSupport.findLastUserIndex(history));
    }

    @Test
    void estimateToolsSchemaTokensHandlesNullAndJson() {
        assertEquals(0, CompactionSupport.estimateToolsSchemaTokens(null));
        assertTrue(CompactionSupport.estimateToolsSchemaTokens("[{\"name\":\"read_file\"}]") > 0);
    }
}
