package com.bettercli.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Custom SubAgent 运行审计（CLI 侧可观测，对标平台 Webhook/Trace 的轻量替代）。
 *
 * <p>JSONL：{@code ~/.bettercli/audit/subagent-YYYY-MM-DD.jsonl}，
 * 可通过 {@code -Dbettercli.audit.dir} / {@code BETTERCLI_AUDIT_DIR} 覆盖目录。
 * 写入失败不影响主流程。
 */
public final class CustomSubAgentAudit {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final Object LOCK = new Object();

    private CustomSubAgentAudit() {
    }

    public static void record(String event, String agentName, String sessionId,
                              String parentConversationId, String detail) {
        try {
            Path dir = auditDir();
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
            synchronized (LOCK) {
                Files.createDirectories(dir);
                Path file = dir.resolve("subagent-" + LocalDate.now() + ".jsonl");
                Files.writeString(file, MAPPER.writeValueAsString(node) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Custom SubAgent 审计写入失败: " + e.getMessage());
        }
    }

    private static Path auditDir() {
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
