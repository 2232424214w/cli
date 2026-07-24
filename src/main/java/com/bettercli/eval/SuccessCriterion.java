package com.bettercli.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 确定性成功标准。最小闭环只做文件/内容判定，开放式任务留给后续 LLM-as-judge。
 */
public record SuccessCriterion(String type, String path, String text) {

    public SuccessCriterion {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("success type required");
        }
        type = type.trim().toLowerCase(Locale.ROOT);
        path = path == null ? "" : path;
        text = text == null ? "" : text;
    }

    static SuccessCriterion fromJson(JsonNode node) {
        return new SuccessCriterion(
                node.path("type").asText(null),
                node.path("path").asText(""),
                node.path("text").asText("")
        );
    }
}
