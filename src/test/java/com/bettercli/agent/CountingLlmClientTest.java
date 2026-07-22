package com.bettercli.agent;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CountingLlmClientTest {

    @Test
    void shouldAccumulateTokensAcrossCalls() throws IOException {
        // stub 返回固定 token 数
        LlmClient stub = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return new ChatResponse("assistant", "ok", null, null, 100, 20, 5);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                return new ChatResponse("assistant", "ok", null, null, 100, 20, 5);
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }

            @Override
            public String getProviderName() {
                return "stub";
            }
        };

        CountingLlmClient counter = new CountingLlmClient(stub);
        counter.chat(List.of(), null);
        counter.chat(List.of(), null, LlmClient.StreamListener.NO_OP);

        assertEquals(2, counter.getCallCount());
        assertEquals(200, counter.getInputTokens());
        assertEquals(40, counter.getOutputTokens());
        assertEquals(10, counter.getCachedInputTokens());

        counter.reset();
        assertEquals(0, counter.getCallCount());
        assertEquals(0, counter.getInputTokens());
    }
}
