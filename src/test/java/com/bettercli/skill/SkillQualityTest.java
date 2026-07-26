package com.bettercli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillQualityTest {

    @Test
    void validatesKebabCaseName() {
        assertTrue(SkillQuality.isValidName("progressive-knowledge"));
        assertTrue(SkillQuality.isValidName("web-access"));
        assertFalse(SkillQuality.isValidName("WebAccess"));
        assertFalse(SkillQuality.isValidName("bad_name"));
        assertFalse(SkillQuality.isValidName("a".repeat(65)));
    }

    @Test
    void validateFlagsShortDescriptionAndFirstPerson() {
        Skill skill = new Skill(
                "Bad_Name",
                "我帮你处理",
                null, null, List.of(),
                Skill.Source.USER,
                "short",
                null, null, List.of());
        List<String> warnings = SkillQuality.validate(skill);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("kebab-case")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("第三人称") || w.contains("过短")), warnings.toString());
    }

    @Test
    void validateFlagsOversizedBody() {
        String body = "word ".repeat(SkillQuality.SOFT_BODY_MAX_WORDS + 50);
        Skill skill = new Skill(
                "long-body-skill",
                "当用户询问超长手册组织时使用，演示正文软上限警告。",
                null, null, List.of(),
                Skill.Source.USER,
                body,
                null, null, List.of());
        List<String> warnings = SkillQuality.validate(skill);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("软上限")), warnings.toString());
        assertTrue(SkillQuality.formatBodySizeHint(skill).contains("正文偏长"));
    }

    @Test
    void formatReferencesGuideEmbedsIndexExcerpt(@TempDir Path dir) throws Exception {
        Path refs = dir.resolve("references");
        Files.createDirectories(refs);
        Files.writeString(refs.resolve("INDEX.md"),
                "| file | summary |\n| --- | --- |\n| a.md | Alpha topic |\n");
        Skill skill = new Skill(
                "kb-demo",
                "当用户需要渐进知识库示例时使用。",
                "1.0.0", "test", List.of("kb"),
                Skill.Source.USER,
                "# body\n",
                dir.resolve("SKILL.md"),
                refs,
                List.of());
        String guide = SkillQuality.formatReferencesGuide(skill);
        assertTrue(guide.contains("INDEX.md 摘要"), guide);
        assertTrue(guide.contains("Alpha topic"), guide);
        assertTrue(guide.contains("read_file"), guide);
    }

    @Test
    void formatReferencesGuideListsFilesWhenIndexMissing(@TempDir Path dir) throws Exception {
        Path refs = dir.resolve("references");
        Files.createDirectories(refs);
        Files.writeString(refs.resolve("notes.md"), "n");
        Skill skill = new Skill(
                "kb-demo",
                "当用户需要无 INDEX 的参考目录时使用。",
                null, null, List.of(),
                Skill.Source.USER,
                "body",
                null,
                refs,
                List.of());
        String guide = SkillQuality.formatReferencesGuide(skill);
        assertTrue(guide.contains("未找到 INDEX.md"), guide);
        assertTrue(guide.contains("notes.md"), guide);
    }
}
