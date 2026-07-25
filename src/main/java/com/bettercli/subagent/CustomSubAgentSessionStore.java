package com.bettercli.subagent;

import com.bettercli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Custom SubAgent 会话落盘（CLI 侧轻量 HA：对标文档 §7 故障恢复）。
 *
 * <p>目录：{@code ~/.bettercli/subagent-sessions/<sessionId>.json}
 * 每轮工具后可增量写入 messages；崩溃后可用 {@code /subagent resume} 从历史续跑。
 */
public final class CustomSubAgentSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGES = 80;
    private static final int MAX_CONTENT = 6000;

    public enum Status {
        RUNNING, DONE, ERROR, CANCELLED, TIMEOUT
    }

    public record SessionRecord(
            String sessionId,
            String agentName,
            String parentConversationId,
            String task,
            String mode,
            Status status,
            String startedAt,
            String updatedAt,
            String resultPreview,
            List<LlmClient.Message> messages
    ) {
    }

    private final Path rootDir;

    public CustomSubAgentSessionStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    public static CustomSubAgentSessionStore defaultStore() {
        String configured = System.getProperty("bettercli.subagent.sessions.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("BETTERCLI_SUBAGENT_SESSIONS_DIR");
        }
        Path dir = (configured != null && !configured.isBlank())
                ? Path.of(configured.trim())
                : Path.of(System.getProperty("user.home"), ".bettercli", "subagent-sessions");
        return new CustomSubAgentSessionStore(dir);
    }

    public Path rootDir() {
        return rootDir;
    }

    public void start(String sessionId, String agentName, String parentId, String task, String mode) {
        SessionRecord record = new SessionRecord(
                sessionId, agentName, parentId, task, mode,
                Status.RUNNING, Instant.now().toString(), Instant.now().toString(),
                null, List.of());
        save(record);
    }

    public void checkpoint(String sessionId, List<LlmClient.Message> messages) {
        SessionRecord existing = load(sessionId);
        if (existing == null) {
            return;
        }
        save(new SessionRecord(
                existing.sessionId(), existing.agentName(), existing.parentConversationId(),
                existing.task(), existing.mode(), Status.RUNNING,
                existing.startedAt(), Instant.now().toString(),
                existing.resultPreview(), truncateMessages(messages)));
    }

    public void finish(String sessionId, Status status, String resultPreview,
                       List<LlmClient.Message> messages) {
        SessionRecord existing = load(sessionId);
        if (existing == null) {
            return;
        }
        String preview = resultPreview == null ? null
                : (resultPreview.length() > 300 ? resultPreview.substring(0, 300) + "..." : resultPreview);
        save(new SessionRecord(
                existing.sessionId(), existing.agentName(), existing.parentConversationId(),
                existing.task(), existing.mode(), status == null ? Status.DONE : status,
                existing.startedAt(), Instant.now().toString(),
                preview, truncateMessages(messages)));
    }

    public SessionRecord load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path file = fileFor(sessionId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            Status status = Status.DONE;
            try {
                status = Status.valueOf(root.path("status").asText("DONE").toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
            }
            List<LlmClient.Message> messages = new ArrayList<>();
            if (root.path("messages").isArray()) {
                for (JsonNode n : root.path("messages")) {
                    String role = n.path("role").asText("");
                    String content = n.path("content").asText("");
                    if (role.isBlank()) {
                        continue;
                    }
                    messages.add(new LlmClient.Message(role, content));
                }
            }
            return new SessionRecord(
                    root.path("sessionId").asText(sessionId),
                    root.path("agentName").asText(""),
                    root.path("parentConversationId").asText(""),
                    root.path("task").asText(""),
                    root.path("mode").asText(""),
                    status,
                    root.path("startedAt").asText(""),
                    root.path("updatedAt").asText(""),
                    root.path("resultPreview").asText(null),
                    messages);
        } catch (Exception e) {
            return null;
        }
    }

    public List<SessionRecord> listRecent(int limit) {
        int n = limit <= 0 ? 20 : Math.min(limit, 100);
        if (!Files.isDirectory(rootDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(rootDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).reversed())
                    .limit(n)
                    .map(p -> {
                        String id = p.getFileName().toString().replace(".json", "");
                        return load(id);
                    })
                    .filter(r -> r != null)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public SessionRecord latestResumable() {
        for (SessionRecord r : listRecent(50)) {
            if (r.status() == Status.RUNNING || r.status() == Status.CANCELLED
                    || r.status() == Status.TIMEOUT || r.status() == Status.ERROR) {
                if (r.messages() != null && !r.messages().isEmpty()) {
                    return r;
                }
            }
        }
        return null;
    }

    public String formatList(int limit) {
        List<SessionRecord> records = listRecent(limit);
        if (records.isEmpty()) {
            return "🧩 Custom SubAgent 会话: 暂无记录（目录 " + rootDir + "）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Custom SubAgent 会话（最近 ").append(records.size()).append("）:\n");
        for (SessionRecord r : records) {
            sb.append("  - ").append(r.sessionId())
                    .append(" [").append(r.status()).append("]")
                    .append(" agent=").append(r.agentName())
                    .append(" mode=").append(r.mode())
                    .append('\n');
            if (r.task() != null && !r.task().isBlank()) {
                String t = r.task().replace('\n', ' ');
                if (t.length() > 60) {
                    t = t.substring(0, 60) + "...";
                }
                sb.append("    task: ").append(t).append('\n');
            }
        }
        sb.append("续跑: /subagent resume [sessionId]（省略则取最近可恢复会话）\n");
        sb.append("目录: ").append(rootDir);
        return sb.toString();
    }

    private void save(SessionRecord record) {
        try {
            Files.createDirectories(rootDir);
            ObjectNode root = MAPPER.createObjectNode();
            root.put("sessionId", record.sessionId());
            root.put("agentName", record.agentName());
            root.put("parentConversationId",
                    record.parentConversationId() == null ? "" : record.parentConversationId());
            root.put("task", record.task() == null ? "" : record.task());
            root.put("mode", record.mode() == null ? "" : record.mode());
            root.put("status", record.status().name());
            root.put("startedAt", record.startedAt());
            root.put("updatedAt", record.updatedAt());
            if (record.resultPreview() != null) {
                root.put("resultPreview", record.resultPreview());
            }
            ArrayNode msgs = root.putArray("messages");
            for (LlmClient.Message m : truncateMessages(record.messages())) {
                ObjectNode n = msgs.addObject();
                n.put("role", m.role());
                n.put("content", m.content() == null ? "" : m.content());
            }
            Files.writeString(fileFor(record.sessionId()),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            System.err.println("⚠️ Custom SubAgent 会话落盘失败: " + e.getMessage());
        }
    }

    private Path fileFor(String sessionId) {
        String safe = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return rootDir.resolve(safe + ".json");
    }

    private static List<LlmClient.Message> truncateMessages(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<LlmClient.Message> src = messages;
        if (src.size() > MAX_MESSAGES) {
            src = src.subList(src.size() - MAX_MESSAGES, src.size());
        }
        List<LlmClient.Message> out = new ArrayList<>(src.size());
        for (LlmClient.Message m : src) {
            if (m == null) {
                continue;
            }
            // 跳过 tool_calls 复杂结构：只保留纯文本轮次，续跑仍可用
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                String content = m.content() == null || m.content().isBlank()
                        ? "(tool_calls omitted in checkpoint)" : m.content();
                out.add(LlmClient.Message.assistant(truncate(content)));
                continue;
            }
            String content = truncate(m.content());
            String role = m.role() == null ? "user" : m.role().toLowerCase(Locale.ROOT);
            if ("tool".equals(role)) {
                out.add(LlmClient.Message.tool(m.toolCallId(), content));
            } else if ("assistant".equals(role)) {
                out.add(LlmClient.Message.assistant(content));
            } else if ("system".equals(role)) {
                out.add(LlmClient.Message.system(content));
            } else {
                out.add(LlmClient.Message.user(content));
            }
        }
        return out;
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= MAX_CONTENT ? content : content.substring(0, MAX_CONTENT) + "...";
    }
}
