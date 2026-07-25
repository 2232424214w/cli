package com.bettercli.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Custom SubAgent 运行审计 + 可选 Webhook（CLI 侧对标 1024 可观测 / SUBAGENT_* 事件）。
 *
 * <p>JSONL：{@code ~/.bettercli/audit/subagent-YYYY-MM-DD.jsonl}
 * <p>Webhook：{@code BETTERCLI_SUBAGENT_WEBHOOK_URL} / {@code -Dbettercli.subagent.webhook.url}，
 * 异步 POST，超时 5s，fail-open。
 */
public final class CustomSubAgentAudit {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object LOCK = new Object();
    private static final ExecutorService WEBHOOK_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "subagent-webhook");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private CustomSubAgentAudit() {
    }

    public static void record(String event, String agentName, String sessionId,
                              String parentConversationId, String detail) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ts", Instant.now().toString());
        node.put("event", event == null ? "unknown" : event);
        if (agentName != null) {
            node.put("subagent_name", agentName);
        }
        if (sessionId != null) {
            node.put("session", sessionId);
        }
        if (parentConversationId != null) {
            node.put("parent_conversation_id", parentConversationId);
        }
        if (detail != null && !detail.isBlank()) {
            String d = detail.length() > 500 ? detail.substring(0, 500) + "..." : detail;
            node.put("detail", d);
        }
        try {
            Path dir = auditDir();
            synchronized (LOCK) {
                Files.createDirectories(dir);
                Path file = dir.resolve("subagent-" + LocalDate.now() + ".jsonl");
                Files.writeString(file, MAPPER.writeValueAsString(node) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Custom SubAgent 审计写入失败: " + e.getMessage());
        }
        maybePostWebhook(node);
    }

    /** 读取最近 {@code limit} 条审计（跨今日文件；limit 默认 20，最大 200）。 */
    public static String formatTail(int limit) {
        int n = limit <= 0 ? 20 : Math.min(limit, 200);
        List<String> lines = readRecentLines(n);
        if (lines.isEmpty()) {
            return "🧩 Custom SubAgent 审计: 暂无记录（目录 " + auditDir() + "）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Custom SubAgent 审计（最近 ").append(lines.size()).append(" 条）:\n");
        for (String line : lines) {
            sb.append("  ").append(line).append('\n');
        }
        sb.append("目录: ").append(auditDir());
        return sb.toString().trim();
    }

    static List<String> readRecentLines(int limit) {
        Path dir = auditDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try {
            List<Path> files = Files.list(dir)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("subagent-") && name.endsWith(".jsonl");
                    })
                    .sorted()
                    .toList();
            List<String> all = new ArrayList<>();
            for (Path file : files) {
                all.addAll(Files.readAllLines(file));
            }
            if (all.size() <= limit) {
                return all;
            }
            return all.subList(all.size() - limit, all.size());
        } catch (Exception e) {
            return List.of("（读取失败: " + e.getMessage() + "）");
        }
    }

    private static void maybePostWebhook(ObjectNode node) {
        String url = System.getProperty("bettercli.subagent.webhook.url");
        if (url == null || url.isBlank()) {
            url = System.getenv("BETTERCLI_SUBAGENT_WEBHOOK_URL");
        }
        if (url == null || url.isBlank()) {
            return;
        }
        final String target = url.trim();
        final String body;
        try {
            body = MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return;
        }
        WEBHOOK_POOL.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // fail-open：Webhook 失败不影响主流程
            }
        });
    }

    public static Path auditDir() {
        String configured = System.getProperty("bettercli.audit.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("BETTERCLI_AUDIT_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return Path.of(System.getProperty("user.home"), ".bettercli", "audit");
    }
}
