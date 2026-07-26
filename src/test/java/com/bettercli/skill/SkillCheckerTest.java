package com.bettercli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillCheckerTest {

    @TempDir
    Path tempDir;

    @Test
    void flagsMissingDependencyAsError() throws IOException {
        writeSkill("main-skill", """
                ---
                name: main-skill
                description: 当用户需要主流程时使用本 skill。
                skill-dependencies:
                  - missing-dep
                ---
                body
                """);
        SkillRegistry registry = registry();
        SkillChecker.Report report = SkillChecker.check(registry.findAnySkill("main-skill"), registry);
        assertFalse(report.ok());
        assertTrue(report.findings().stream()
                .anyMatch(f -> f.severity() == SkillChecker.Severity.ERROR
                        && f.message().contains("missing-dep")));
    }

    @Test
    void okSkillHasNoErrors() throws IOException {
        writeSkill("good-skill", """
                ---
                name: good-skill
                description: 当用户需要演示合格 skill 时使用。
                ---
                # good
                steps here
                """);
        SkillRegistry registry = registry();
        SkillChecker.Report report = SkillChecker.check(registry.findAnySkill("good-skill"), registry);
        assertTrue(report.ok());
        assertEquals(0, report.errors());
    }

    private SkillRegistry registry() {
        SkillRegistry registry = new SkillRegistry(null, tempDir.resolve("user-skills"), null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        return registry;
    }

    private void writeSkill(String name, String content) throws IOException {
        Path dir = tempDir.resolve("user-skills").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
