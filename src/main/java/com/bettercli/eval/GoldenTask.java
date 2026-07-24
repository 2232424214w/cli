package com.bettercli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agent 任务级 Eval 黄金任务。
 *
 * <p>一行 JSON：id / mode / input / success 判定列表。mode 目前最小闭环只跑 react；
 * plan/team 入口预留字段，后续接 PlanExecuteAgent / AgentOrchestrator。
 */
public record GoldenTask(
        String id,
        String mode,
        String category,
        String input,
        List<SuccessCriterion> success
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GoldenTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("task id required");
        }
        mode = mode == null || mode.isBlank() ? "react" : mode.trim().toLowerCase(Locale.ROOT);
        category = category == null ? "" : category;
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("task input required: " + id);
        }
        success = success == null ? List.of() : List.copyOf(success);
        if (success.isEmpty()) {
            throw new IllegalArgumentException("task success criteria required: " + id);
        }
    }

    public static List<GoldenTask> loadJsonl(Path path) throws IOException {
        List<GoldenTask> tasks = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                try {
                    tasks.add(parse(line));
                } catch (RuntimeException e) {
                    throw new IOException("Invalid golden task at line " + lineNo + ": " + e.getMessage(), e);
                }
            }
        }
        return List.copyOf(tasks);
    }

    public static GoldenTask parse(String jsonLine) throws IOException {
        JsonNode root = MAPPER.readTree(jsonLine);
        List<SuccessCriterion> criteria = new ArrayList<>();
        JsonNode successNode = root.get("success");
        if (successNode != null && successNode.isArray()) {
            for (JsonNode item : successNode) {
                criteria.add(SuccessCriterion.fromJson(item));
            }
        }
        return new GoldenTask(
                text(root, "id"),
                text(root, "mode"),
                text(root, "category"),
                text(root, "input"),
                criteria
        );
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
