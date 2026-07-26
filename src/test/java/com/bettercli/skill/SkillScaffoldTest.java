package com.bettercli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillScaffoldTest {

    @Test
    void createsSkillMdAndIndex(@TempDir Path tempDir) throws Exception {
        Path skillMd = SkillScaffold.create(tempDir, "my-workflow");
        assertTrue(Files.isRegularFile(skillMd));
        String content = Files.readString(skillMd);
        assertTrue(content.contains("name: my-workflow"));
        assertTrue(Files.isRegularFile(tempDir.resolve("my-workflow/references/INDEX.md")));
    }

    @Test
    void rejectsInvalidName(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class,
                () -> SkillScaffold.create(tempDir, "Bad_Name"));
    }

    @Test
    void rejectsExistingDir(@TempDir Path tempDir) throws Exception {
        SkillScaffold.create(tempDir, "dup-skill");
        assertThrows(Exception.class, () -> SkillScaffold.create(tempDir, "dup-skill"));
    }
}
