package com.bettercli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow 检查点持久化（每 runId 一个 JSON 文件）。
 * 与 DurableTask 的 task id 对齐时，崩溃重入队后可从断点续跑。
 */
public class WorkflowCheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path rootDir;

    public WorkflowCheckpointStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    public void save(WorkflowCheckpoint checkpoint) throws IOException {
        Files.createDirectories(rootDir);
        Path file = fileFor(checkpoint.runId());
        ObjectNode root = MAPPER.createObjectNode();
        root.put("runId", checkpoint.runId());
        root.put("goal", checkpoint.goal());
        root.put("snapshotId", checkpoint.snapshotId());
        root.put("savedAtEpochMs", checkpoint.savedAtEpochMs());
        ArrayNode ids = root.putArray("executedStepIds");
        for (String id : checkpoint.executedStepIds()) {
            ids.add(id);
        }
        ObjectNode arts = root.putObject("artifacts");
        for (var e : checkpoint.artifacts().entrySet()) {
            arts.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    public Optional<WorkflowCheckpoint> load(String runId) throws IOException {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        Path file = fileFor(runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var tree = MAPPER.readTree(Files.readString(file));
        List<String> ids = new ArrayList<>();
        if (tree.path("executedStepIds").isArray()) {
            for (var n : tree.path("executedStepIds")) {
                ids.add(n.asText());
            }
        }
        Map<String, String> arts = new LinkedHashMap<>();
        var artsNode = tree.path("artifacts");
        if (artsNode.isObject()) {
            Iterator<String> fields = artsNode.fieldNames();
            while (fields.hasNext()) {
                String k = fields.next();
                arts.put(k, artsNode.path(k).asText(""));
            }
        }
        return Optional.of(new WorkflowCheckpoint(
                tree.path("runId").asText(runId),
                tree.path("goal").asText(""),
                ids,
                arts,
                tree.path("snapshotId").asText(""),
                tree.path("savedAtEpochMs").asLong(0)
        ));
    }

    public void delete(String runId) throws IOException {
        Files.deleteIfExists(fileFor(runId));
    }

    private Path fileFor(String runId) {
        String safe = runId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return rootDir.resolve(safe + ".checkpoint.json");
    }
}
