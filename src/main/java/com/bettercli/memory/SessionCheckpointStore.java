package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 会话 JSONL 持久化：普通消息行 + {@code compacted} 检查点行。
 *
 * <p>加载时遇到 {@code compacted} 用内嵌快照整表替换历史，再追加其后新消息（对标 1024 Resume 快进）。
 * Rotate：有检查点时从最新 compacted 起整段保留（只丢检查点前 raw 行）；
 * 若文件仍从首行 compacted 起膨胀，则物理合并为单行快照（不丢 Resume 内容）；无检查点则按最近 N 行截断。
 */
public class SessionCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(SessionCheckpointStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_ROTATE_KEEP_LINES = 400;

    private final Path file;
    private final int rotateKeepLines;

    public SessionCheckpointStore(Path file) {
        this(file, DEFAULT_ROTATE_KEEP_LINES);
    }

    public SessionCheckpointStore(Path file, int rotateKeepLines) {
        this.file = file;
        this.rotateKeepLines = Math.max(50, rotateKeepLines);
    }

    public Path file() {
        return file;
    }

    public synchronized void appendMessage(LlmClient.Message message) {
        if (message == null || "system".equals(message.role())) {
            return;
        }
        try {
            ensureParent();
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", message.role());
            writeMessageFields(node, message);
            appendLine(node);
            maybeRotate();
        } catch (Exception e) {
            log.warn("appendMessage failed: {}", e.toString());
        }
    }

    public synchronized void appendCompacted(ConversationHistoryCompactor.CompactCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        try {
            ensureParent();
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "compacted");
            node.put("trigger", checkpoint.trigger().name());
            node.put("summary", checkpoint.summaryText() == null ? "" : checkpoint.summaryText());
            ArrayNode snapshot = node.putArray("history");
            for (LlmClient.Message msg : checkpoint.replacementHistory()) {
                ObjectNode item = snapshot.addObject();
                item.put("role", msg.role());
                writeMessageFields(item, msg);
            }
            appendLine(node);
            maybeRotate();
        } catch (Exception e) {
            log.warn("appendCompacted failed (memory compaction kept): {}", e.toString());
        }
    }

    /**
     * 从 session 文件重建非 system 历史（含 compacted 快进）。
     */
    public synchronized List<LlmClient.Message> loadHistory() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return reconstructHistory(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("loadHistory failed: {}", e.toString());
            return List.of();
        }
    }

    public synchronized void maybeRotate() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= rotateKeepLines) {
                return;
            }
            int lastCompacted = -1;
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                if (line.contains("\"type\":\"compacted\"") || line.contains("\"type\": \"compacted\"")) {
                    lastCompacted = i;
                    break;
                }
            }
            List<String> kept;
            if (lastCompacted >= 0) {
                // 对标 1024：从最新 compacted 起整段保留（含其后全部消息），只丢掉检查点之前的 raw 行。
                if (lastCompacted == 0) {
                    if (lines.size() <= rotateKeepLines * 2) {
                        return;
                    }
                    // 首行已是最新检查点且文件仍膨胀：把有效 Resume 历史物理合并成单行 compacted
                    kept = List.of(serializeCompactedSnapshot(
                            CompactTrigger.MANUAL.name(),
                            "[上下文检查点] （session 物理合并，内容未再摘要）\n",
                            reconstructHistory(lines)));
                } else {
                    kept = new ArrayList<>(lines.subList(lastCompacted, lines.size()));
                }
            } else {
                int from = Math.max(0, lines.size() - rotateKeepLines);
                kept = new ArrayList<>(lines.subList(from, lines.size()));
            }
            if (kept.size() >= lines.size()) {
                return;
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".rotate.tmp");
            Files.write(tmp, kept, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("session rotated: kept {} lines from {}", kept.size(), file);
        } catch (Exception e) {
            log.warn("session rotate failed: {}", e.toString());
        }
    }

    private static List<LlmClient.Message> reconstructHistory(List<String> lines) throws IOException {
        List<LlmClient.Message> history = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            String type = text(node, "type");
            if ("compacted".equals(type)) {
                history.clear();
                JsonNode snap = node.get("history");
                if (snap != null && snap.isArray()) {
                    for (JsonNode item : snap) {
                        LlmClient.Message msg = readMessage(item);
                        if (msg != null) {
                            history.add(msg);
                        }
                    }
                } else {
                    String summary = text(node, "summary");
                    if (summary != null && !summary.isBlank()) {
                        history.add(LlmClient.Message.user(summary));
                    }
                }
                continue;
            }
            LlmClient.Message msg = readMessage(node);
            if (msg != null) {
                history.add(msg);
            }
        }
        return history;
    }

    private static String serializeCompactedSnapshot(String trigger,
                                                     String summary,
                                                     List<LlmClient.Message> history) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "compacted");
        node.put("trigger", trigger == null ? "MANUAL" : trigger);
        node.put("summary", summary == null ? "" : summary);
        ArrayNode snapshot = node.putArray("history");
        if (history != null) {
            for (LlmClient.Message msg : history) {
                ObjectNode item = snapshot.addObject();
                item.put("role", msg.role());
                writeMessageFields(item, msg);
            }
        }
        return MAPPER.writeValueAsString(node);
    }

    private void appendLine(ObjectNode node) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(MAPPER.writeValueAsString(node));
            writer.newLine();
        }
    }

    private void ensureParent() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static void writeMessageFields(ObjectNode node, LlmClient.Message message) {
        if (message.content() != null) {
            node.put("content", message.content());
        }
        if (message.reasoningContent() != null) {
            node.put("reasoning", message.reasoningContent());
        }
        if (message.toolCallId() != null) {
            node.put("toolCallId", message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ArrayNode arr = node.putArray("toolCalls");
            for (LlmClient.ToolCall tc : message.toolCalls()) {
                ObjectNode t = arr.addObject();
                t.put("id", tc.id());
                if (tc.function() != null) {
                    t.put("name", tc.function().name());
                    t.put("arguments", tc.function().arguments());
                }
            }
        }
    }

    private static LlmClient.Message readMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String role = text(node, "role");
        if (role == null || role.isBlank()) {
            role = text(node, "type");
        }
        if (role == null || role.isBlank() || "compacted".equals(role) || "system".equals(role)) {
            return null;
        }
        String content = text(node, "content");
        String reasoning = text(node, "reasoning");
        String toolCallId = text(node, "toolCallId");
        List<LlmClient.ToolCall> toolCalls = null;
        JsonNode tcNode = node.get("toolCalls");
        if (tcNode != null && tcNode.isArray() && !tcNode.isEmpty()) {
            toolCalls = new ArrayList<>();
            for (JsonNode t : tcNode) {
                String id = text(t, "id");
                String name = text(t, "name");
                String args = text(t, "arguments");
                toolCalls.add(new LlmClient.ToolCall(id,
                        new LlmClient.ToolCall.Function(name == null ? "" : name, args == null ? "{}" : args)));
            }
        }
        return new LlmClient.Message(role, content, reasoning, toolCalls, toolCallId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
