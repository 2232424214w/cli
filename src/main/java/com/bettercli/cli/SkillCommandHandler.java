package com.bettercli.cli;

import com.bettercli.llm.LlmClient;
import com.bettercli.skill.Skill;
import com.bettercli.skill.SkillChecker;
import com.bettercli.skill.SkillDraftGenerator;
import com.bettercli.skill.SkillQuality;
import com.bettercli.skill.SkillRegistry;
import com.bettercli.skill.SkillScaffold;
import com.bettercli.skill.SkillStateStore;
import com.bettercli.skill.SkillZipPackager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /skill 命令组的展示与状态切换逻辑。
 * 抽出独立类便于单测；Main.java 只负责 dispatch + 打印。
 */
final class SkillCommandHandler {

    private SkillCommandHandler() {
    }

    static String startupSummary(SkillRegistry registry) {
        List<Skill> all = registry.allSkills();
        if (all.isEmpty()) {
            return "📚 Skills: 未发现可用 skill";
        }
        List<Skill> enabled = registry.enabledSkills();
        return "📚 Skills: " + enabled.size() + "/" + all.size() + " 启用";
    }

    static String list(SkillRegistry registry) {
        List<Skill> all = registry.allSkills();
        if (all.isEmpty()) {
            return "📚 Skills: 未发现可用 skill\n   /skill reload 重新扫描";
        }
        List<Skill> enabled = registry.enabledSkills();
        StringBuilder sb = new StringBuilder("📚 Skills（" + all.size() + " 个）\n");
        for (Skill skill : all) {
            boolean isEnabled = enabled.contains(skill);
            sb.append(String.format("  %s %-16s %-8s %-8s %s%n",
                    isEnabled ? "●" : "○",
                    skill.name(),
                    skill.displaySource(),
                    skill.version() == null ? "" : "v" + skill.version(),
                    abbreviate(skill.description(), 80)));
        }
        sb.append('\n')
                .append("提示：\n")
                .append("  /skill show <name> 看完整 SKILL.md\n")
                .append("  /skill check [name] 本地核查\n")
                .append("  /skill new <name> [--project] 创建骨架\n")
                .append("  /skill import|export … ZIP 打包\n")
                .append("  /skill draft [name] 从会话生成草稿\n")
                .append("  /skill on/off <name> 切换启用状态\n")
                .append("  /skill reload 重新扫描");
        return sb.toString();
    }

    static String show(SkillRegistry registry, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill show web-access";
        }
        Skill skill = registry.findAnySkill(name);
        if (skill == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📖 Skill: ").append(skill.name())
                .append(" (").append(skill.displaySource())
                .append(skill.version() == null ? "" : ", v" + skill.version())
                .append(")\n");
        sb.append("  路径: ").append(skill.skillMdPath()).append('\n');
        if (skill.referencesDir() != null) {
            sb.append("  references/: ").append(skill.referencesDir()).append('\n');
            Path index = skill.referencesDir().resolve("INDEX.md");
            if (Files.isRegularFile(index)) {
                sb.append("  INDEX.md: ").append(index.toAbsolutePath().normalize()).append('\n');
            }
        }
        List<String> quality = SkillQuality.validate(skill);
        if (!quality.isEmpty()) {
            sb.append("  质量提示:\n");
            for (String w : quality) {
                sb.append("    - ").append(w).append('\n');
            }
        }
        sb.append('\n');
        sb.append("---\n");
        sb.append("name: ").append(skill.name()).append('\n');
        sb.append("description: ").append(skill.description()).append('\n');
        if (skill.version() != null) sb.append("version: \"").append(skill.version()).append("\"\n");
        if (skill.author() != null) sb.append("author: ").append(skill.author()).append('\n');
        if (!skill.tags().isEmpty()) sb.append("tags: ").append(skill.tags()).append('\n');
        if (!skill.dependencies().isEmpty()) {
            sb.append("skill-dependencies: ").append(skill.dependencies()).append('\n');
        }
        sb.append("---\n\n");
        sb.append(skill.body());
        return sb.toString();
    }

    static String check(SkillRegistry registry, String nameOrBlank) {
        if (nameOrBlank == null || nameOrBlank.isBlank()) {
            return SkillChecker.formatAll(SkillChecker.checkAll(registry));
        }
        Skill skill = registry.findAnySkill(nameOrBlank.trim());
        if (skill == null) {
            return "❌ Skill 未找到: " + nameOrBlank + "（用 /skill list 查看可用 skill）";
        }
        return "🔍 Skill 检查\n\n" + SkillChecker.format(SkillChecker.check(skill, registry));
    }

    static String createNew(SkillRegistry registry, String payload) {
        ParsedFlags flags = ParsedFlags.parse(payload);
        if (flags.positional.isEmpty()) {
            return "❌ 用法: /skill new <kebab-name> [--project]";
        }
        String name = flags.positional.get(0);
        Path root = resolveSkillsRoot(registry, flags.project);
        if (root == null) {
            return "❌ 无法解析 skills 目录（" + (flags.project ? "project" : "user") + "）";
        }
        try {
            Path skillMd = SkillScaffold.create(root, name);
            registry.reload();
            return "✅ 已创建 skill 骨架: " + name + "\n"
                    + "  SKILL.md: " + skillMd.toAbsolutePath().normalize() + "\n"
                    + "  作用域: " + (flags.project ? "project (.bettercli/skills)" : "user (~/.bettercli/skills)") + "\n"
                    + "  下一步: 编辑后 /skill check " + name;
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        } catch (IOException e) {
            return "❌ 创建失败: " + e.getMessage();
        }
    }

    static String exportZip(SkillRegistry registry, String payload) {
        ParsedFlags flags = ParsedFlags.parse(payload);
        if (flags.positional.isEmpty()) {
            return "❌ 用法: /skill export <name> [path.zip]";
        }
        String name = flags.positional.get(0);
        Skill skill = registry.findAnySkill(name);
        if (skill == null) {
            return "❌ Skill 未找到: " + name;
        }
        Path zipPath;
        if (flags.positional.size() >= 2) {
            zipPath = Path.of(flags.positional.get(1));
        } else {
            zipPath = Path.of(System.getProperty("user.home"), ".bettercli", "skill-exports", name + ".zip");
        }
        try {
            Path written = SkillZipPackager.exportZip(skill, zipPath);
            return "✅ 已导出: " + written;
        } catch (Exception e) {
            return "❌ 导出失败: " + e.getMessage();
        }
    }

    static String importZip(SkillRegistry registry, String payload) {
        ParsedFlags flags = ParsedFlags.parse(payload);
        if (flags.positional.isEmpty()) {
            return "❌ 用法: /skill import <path.zip> [--project] [--force]";
        }
        Path zip = Path.of(flags.positional.get(0));
        Path root = resolveSkillsRoot(registry, flags.project);
        if (root == null) {
            return "❌ 无法解析 skills 目录（" + (flags.project ? "project" : "user") + "）";
        }
        try {
            SkillZipPackager.ImportResult result = SkillZipPackager.importZip(zip, root, flags.force);
            registry.reload();
            return "✅ 已导入 skill: " + result.skillName() + "\n"
                    + "  目录: " + result.skillDir() + "\n"
                    + "  作用域: " + (flags.project ? "project" : "user") + "\n"
                    + "  可用 /skill check " + result.skillName() + " 核查";
        } catch (Exception e) {
            return "❌ 导入失败: " + e.getMessage();
        }
    }

    static String draft(SkillRegistry registry,
                        List<LlmClient.Message> history,
                        LlmClient llmClient,
                        String payload) {
        ParsedFlags flags = ParsedFlags.parse(payload);
        String name = flags.positional.isEmpty() ? null : flags.positional.get(0);
        Path root = resolveSkillsRoot(registry, flags.project);
        if (root == null) {
            return "❌ 无法解析 skills 目录";
        }
        try {
            SkillDraftGenerator.DraftResult result =
                    SkillDraftGenerator.generate(root, name, history, llmClient);
            registry.reload();
            return "✅ 已生成 skill 草稿: " + result.skillName() + "\n"
                    + "  文件: " + result.skillMd() + "\n"
                    + "  来源: " + (result.fromLlm() ? "LLM" : "启发式模板") + "\n"
                    + "  请人工校对后 /skill check " + result.skillName();
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        } catch (IOException e) {
            return "❌ 生成失败: " + e.getMessage();
        }
    }

    static String reload(SkillRegistry registry) {
        registry.reload();
        StringBuilder sb = new StringBuilder();
        sb.append("🔄 已重新扫描 skill 目录\n");
        sb.append(startupSummary(registry)).append('\n');
        List<String> warnings = registry.warnings();
        if (!warnings.isEmpty()) {
            sb.append("⚠️ 质量/解析提示（").append(warnings.size()).append("）:\n");
            int shown = 0;
            for (String w : warnings) {
                sb.append("  - ").append(w).append('\n');
                if (++shown >= 12) {
                    sb.append("  ... 其余 ").append(warnings.size() - shown).append(" 条省略\n");
                    break;
                }
            }
        }
        sb.append("✅ 下一轮 LLM 调用生效");
        return sb.toString();
    }

    static String enable(SkillRegistry registry, SkillStateStore stateStore, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill on web-access";
        }
        if (registry.findAnySkill(name) == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        stateStore.enable(name);
        return "▶️ 已启用 skill: " + name + "（下一轮 LLM 调用生效）";
    }

    static String disable(SkillRegistry registry, SkillStateStore stateStore, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill off web-access";
        }
        if (registry.findAnySkill(name) == null) {
            return "❌ Skill 未找到: " + name;
        }
        stateStore.disable(name);
        return "⏸️ 已禁用 skill: " + name + "（已写入 ~/.bettercli/skills.json，下一轮 LLM 调用生效）";
    }

    private static Path resolveSkillsRoot(SkillRegistry registry, boolean project) {
        if (registry == null) {
            return null;
        }
        Path root = project ? registry.projectSkillsDir() : registry.userSkillsDir();
        if (root == null && !project) {
            root = Path.of(System.getProperty("user.home"), ".bettercli", "skills");
        }
        if (root == null && project) {
            root = Path.of(".bettercli", "skills").toAbsolutePath();
        }
        return root;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 解析 `--project` / `--force` 与位置参数。 */
    record ParsedFlags(List<String> positional, boolean project, boolean force) {
        static ParsedFlags parse(String payload) {
            List<String> positional = new ArrayList<>();
            boolean project = false;
            boolean force = false;
            if (payload == null || payload.isBlank()) {
                return new ParsedFlags(positional, false, false);
            }
            for (String token : payload.trim().split("\\s+")) {
                String t = token.toLowerCase(Locale.ROOT);
                if ("--project".equals(t) || "-p".equals(t)) {
                    project = true;
                } else if ("--force".equals(t) || "-f".equals(t)) {
                    force = true;
                } else if (!token.isBlank()) {
                    positional.add(token);
                }
            }
            return new ParsedFlags(List.copyOf(positional), project, force);
        }
    }
}
