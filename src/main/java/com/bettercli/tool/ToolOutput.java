package com.bettercli.tool;

import com.bettercli.llm.LlmClient;

import java.util.List;

public record ToolOutput(String text, List<LlmClient.ContentPart> imageParts, ToolStatus status) {
    public ToolOutput {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        status = status == null ? ToolStatus.ok() : status;
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(text, imageParts, ToolStatus.ok());
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), ToolStatus.ok());
    }

    public static ToolOutput text(String text, ToolStatus status) {
        return new ToolOutput(text, List.of(), status);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
