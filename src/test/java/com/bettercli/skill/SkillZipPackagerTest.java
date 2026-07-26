package com.bettercli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillZipPackagerTest {

    @TempDir
    Path tempDir;

    @Test
    void exportThenImportRoundTrip() throws Exception {
        Path userRoot = tempDir.resolve("user-skills");
        Path skillMd = SkillScaffold.create(userRoot, "pack-me");
        SkillRegistry registry = new SkillRegistry(null, userRoot, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        Skill skill = registry.findAnySkill("pack-me");
        assertNotNull(skill);

        Path zip = tempDir.resolve("out/pack-me.zip");
        Path written = SkillZipPackager.exportZip(skill, zip);
        assertTrue(Files.isRegularFile(written));

        Path importRoot = tempDir.resolve("imported");
        SkillZipPackager.ImportResult result = SkillZipPackager.importZip(written, importRoot, false);
        assertEquals("pack-me", result.skillName());
        assertTrue(Files.isRegularFile(result.skillMd()));
        assertTrue(Files.readString(result.skillMd()).contains("name: pack-me"));
        assertTrue(Files.isRegularFile(importRoot.resolve("pack-me/references/INDEX.md")));
        assertEquals(skillMd.getFileName().toString(), "SKILL.md");
    }

    @Test
    void importRejectsDuplicateWithoutForce() throws Exception {
        Path root = tempDir.resolve("skills");
        SkillScaffold.create(root, "pack-me");
        SkillRegistry registry = new SkillRegistry(null, root, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        Path zip = SkillZipPackager.exportZip(registry.findAnySkill("pack-me"), tempDir.resolve("a.zip"));

        assertThrows(Exception.class, () -> SkillZipPackager.importZip(zip, root, false));
        assertDoesNotThrow(() -> SkillZipPackager.importZip(zip, root, true));
    }
}
