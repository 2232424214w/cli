package com.bettercli.eval;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicScorerTest {

    @Test
    void scoresFileExistsAndContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hello.txt"), "hello world\n");
        var outcome = DeterministicScorer.score(dir, List.of(
                new SuccessCriterion("file_exists", "hello.txt", ""),
                new SuccessCriterion("content_contains", "hello.txt", "hello"),
                new SuccessCriterion("content_equals", "hello.txt", "hello world")
        ));
        assertTrue(outcome.success());
    }

    @Test
    void failsMissingFile(@TempDir Path dir) {
        var outcome = DeterministicScorer.score(dir, List.of(
                new SuccessCriterion("file_exists", "missing.txt", "")
        ));
        assertFalse(outcome.success());
        assertTrue(outcome.details().get(0).startsWith("FAIL"));
    }
}
