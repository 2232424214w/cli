package com.bettercli.agent;

import com.bettercli.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LlmClient 计数包装器：委托给真实 client，同时累计 input/output token 与调用次数，
 * 供 Multi-Agent ablation benchmark 度量。线程安全（并行批次会并发调用）。
 */
public class CountingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicInteger callCount = new AtomicInteger();

    public CountingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        ChatResponse response = delegate.chat(messages, tools, listener);
        record(response);
        return response;
    }

    private void record(ChatResponse response) {
        if (response == null) {
            return;
        }
        callCount.incrementAndGet();
        inputTokens.addAndGet(response.inputTokens());
        outputTokens.addAndGet(response.outputTokens());
        cachedInputTokens.addAndGet(response.cachedInputTokens());
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsImageInput() {
        return delegate.supportsImageInput();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    public long getInputTokens() {
        return inputTokens.get();
    }

    public long getOutputTokens() {
        return outputTokens.get();
    }

    public long getCachedInputTokens() {
        return cachedInputTokens.get();
    }

    public int getCallCount() {
        return callCount.get();
    }

    public void reset() {
        inputTokens.set(0);
        outputTokens.set(0);
        cachedInputTokens.set(0);
        callCount.set(0);
    }
}
