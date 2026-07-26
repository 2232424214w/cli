package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * session_search 第 4 阶段：并行 LLM 摘要（对标 1024）。
 * 超时 / 失败降级为 raw preview，不拖垮工具主路径。
 */
public final class SessionSearchSummarizer {

    private static final Logger log = LoggerFactory.getLogger(SessionSearchSummarizer.class);

    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int PREVIEW_CHARS = 500;

    public record SessionSummary(
            String conversationId,
            String summary,
            String status  // ok | degraded
    ) {
    }

    private SessionSearchSummarizer() {
    }

    public static List<SessionSummary> summarizeAll(List<SessionMessageSearchResult> results,
                                                    String query,
                                                    LlmClient llmClient,
                                                    long timeoutSeconds) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (llmClient == null) {
            return results.stream().map(r -> degraded(r, query)).toList();
        }
        long timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, results.size()), r -> {
            Thread t = new Thread(r, "session-search-summarize");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<SessionSummary>> futures = new ArrayList<>();
            for (SessionMessageSearchResult result : results) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> summarizeOne(result, query, llmClient), pool));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            try {
                all.get(timeout, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("session_search 摘要整体超时 {}s，未完成会话降级为 preview", timeout);
            } catch (Exception e) {
                log.warn("session_search 摘要等待失败: {}", e.toString());
            }
            List<SessionSummary> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<SessionSummary> f = futures.get(i);
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    try {
                        out.add(f.getNow(degraded(results.get(i), query)));
                        continue;
                    } catch (Exception ignored) {
                        // fall through
                    }
                }
                f.cancel(true);
                out.add(degraded(results.get(i), query));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static SessionSummary summarizeOne(SessionMessageSearchResult result, String query, LlmClient llmClient) {
        String window = SessionSearchWindow.formatWithWindow(
                result.getFullConversation(), query, SessionSearchWindow.DEFAULT_MAX_CHARS);
        if (window.length() > 12_000) {
            window = window.substring(0, 12_000) + "\n...(摘要输入已截断)";
        }
        String prompt = """
                请用中文摘要下列历史会话（与查询相关）。要点覆盖：用户意图、行动结果、关键决策、技术细节、遗留问题。
                控制在 200 字以内，不要编造未出现的内容。

                查询: %s
                会话 ID: %s

                --- 会话内容 ---
                %s
                """.formatted(query == null ? "" : query, result.getConversationId(), window);
        try {
            LlmClient.ChatResponse response = llmClient.chat(
                    List.of(LlmClient.Message.user(prompt)), null);
            String content = response == null || response.content() == null
                    ? "" : response.content().strip();
            if (content.isBlank()) {
                return degraded(result, query);
            }
            return new SessionSummary(result.getConversationId(), content, "ok");
        } catch (Exception e) {
            log.debug("session_search 单会话摘要失败 {}: {}", result.getConversationId(), e.toString());
            return degraded(result, query);
        }
    }

    public static SessionSummary degraded(SessionMessageSearchResult result, String query) {
        String preview = SessionSearchWindow.formatWithWindow(
                result.getFullConversation(), query, PREVIEW_CHARS * 4);
        if (preview.length() > PREVIEW_CHARS) {
            preview = preview.substring(0, PREVIEW_CHARS) + "...";
        }
        return new SessionSummary(result.getConversationId(), preview, "degraded");
    }
}
