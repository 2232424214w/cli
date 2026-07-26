package com.bettercli.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在用户级或项目级 skills 目录下生成可编辑的 Skill 骨架。
 */
public final class SkillScaffold {

    private SkillScaffold() {
    }

    public static Path create(Path skillsRoot, String name) throws IOException {
        if (skillsRoot == null) {
            throw new IllegalArgumentException("skills 根目录不能为空");
        }
        if (!SkillQuality.isValidName(name)) {
            throw new IllegalArgumentException(
                    "name 必须为 kebab-case（小写字母/数字/连字符，≤" + SkillQuality.MAX_NAME_LENGTH + "）: " + name);
        }
        Path dir = skillsRoot.resolve(name);
        if (Files.exists(dir)) {
            throw new IOException("目录已存在: " + dir.toAbsolutePath().normalize());
        }
        Files.createDirectories(dir.resolve("references"));
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, template(name), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("references").resolve("INDEX.md"), indexTemplate(name), StandardCharsets.UTF_8);
        return skillMd;
    }

    static String template(String name) {
        return """
                ---
                name: %s
                description: |
                  当用户需要【在此填写触发场景】时使用本 skill。
                  说明功能与适用边界，便于索引发现。
                version: "0.1.0"
                author: local
                tags: [draft]
                ---

                # %s

                ## 何时使用

                - 触发场景 1
                - 触发场景 2

                ## 决策步骤

                1. 确认用户目标
                2. 按需 `read_file` references（先 INDEX.md）
                3. 用工具完成任务并汇报

                ## 注意事项

                - 保持本文件简短；细节放 `references/`
                - 需要其它 skill 时在 frontmatter 声明 `skill-dependencies`
                """.formatted(name, name);
    }

    static String indexTemplate(String name) {
        return """
                # %s references

                | 文件 | 摘要 |
                | --- | --- |
                | （示例）notes.md | 补充说明 |

                > 新增文档后在此追加一行，供 load_skill 渐进加载。
                """.formatted(name);
    }
}
