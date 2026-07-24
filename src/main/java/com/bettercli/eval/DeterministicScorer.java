package com.bettercli.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性判分：file_exists / content_contains / content_equals。
 * 全部相对 workspace 根目录解析，拒绝越界路径。
 */
public final class DeterministicScorer {

    private DeterministicScorer() {
    }

    public static ScoreOutcome score(Path workspace, List<SuccessCriterion> criteria) {
        List<String> details = new ArrayList<>();
        boolean allPass = true;
        for (SuccessCriterion criterion : criteria) {
            try {
                Path target = resolveInside(workspace, criterion.path());
                boolean pass = switch (criterion.type()) {
                    case "file_exists" -> Files.isRegularFile(target);
                    case "content_contains" -> Files.isRegularFile(target)
                            && Files.readString(target, StandardCharsets.UTF_8).contains(criterion.text());
                    case "content_equals" -> Files.isRegularFile(target)
                            && Files.readString(target, StandardCharsets.UTF_8).trim().equals(criterion.text().trim());
                    default -> throw new IllegalArgumentException("unsupported success type: " + criterion.type());
                };
                details.add((pass ? "PASS" : "FAIL") + " " + criterion.type()
                        + (criterion.path().isBlank() ? "" : " path=" + criterion.path())
                        + (criterion.text().isBlank() ? "" : " text=" + truncate(criterion.text())));
                allPass &= pass;
            } catch (Exception e) {
                allPass = false;
                details.add("FAIL " + criterion.type() + " path=" + criterion.path() + " error=" + e.getMessage());
            }
        }
        return new ScoreOutcome(allPass, List.copyOf(details));
    }

    private static Path resolveInside(Path workspace, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        Path root = workspace.toRealPath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + relative);
        }
        return resolved;
    }

    private static String truncate(String text) {
        if (text.length() <= 40) {
            return text;
        }
        return text.substring(0, 37) + "...";
    }

    public record ScoreOutcome(boolean success, List<String> details) {
    }
}
