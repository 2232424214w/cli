package com.bettercli.subagent;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BgReactClientResolverTest {

    @AfterEach
    void clear() {
        System.clearProperty("bettercli.bg.react.provider");
        System.clearProperty("bettercli.bg.react.model");
    }

    @Test
    void fallsBackWhenProviderUnset() {
        LlmClient main = stub("main", "m");
        assertSame(main, BgReactClientResolver.resolve(main, null));
    }

    @Test
    void fallsBackWhenProviderUnavailable() {
        System.setProperty("bettercli.bg.react.provider", "definitely-not-a-provider");
        LlmClient main = stub("main", "m");
        // config load may still fail create → fallback
        LlmClient resolved = BgReactClientResolver.resolve(main);
        assertSame(main, resolved);
    }

    private static LlmClient stub(String provider, String model) {
        return new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return chat(messages, tools, StreamListener.NO_OP);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                return new ChatResponse("assistant", "x", null, 1, 1);
            }

            @Override
            public String getModelName() {
                return model;
            }

            @Override
            public String getProviderName() {
                return provider;
            }
        };
    }
}
