package com.bettercli.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Skill 核查（对标 1024「检查 Skill」）：质量护栏 + 目录结构 + 依赖可解析性。
 * 不调用 LLM；结果供 {@code /skill check} 展示。
 */
public final class SkillChecker {

    public enum Severity {
        ERROR, WARN, INFO
    }

    public record Finding(Severity severity, String message) {
    }

    public record Report(String skillName, List<Finding> findings) {
        public long errors() {
            return findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        }

        public long warnings() {
            return findings.stream().filter(f -> f.severity() == Severity.WARN).count();
        }

        public boolean ok() {
            return errors() == 0;
        }
    }

    private SkillChecker() {
    }

    public static Report check(Skill skill, SkillRegistry registry) {
        List<Finding> findings = new ArrayList<>();
        if (skill == null) {
            findings.add(new Finding(Severity.ERROR, "skill 为 null"));
            return new Report("(null)", findings);
        }

        if (skill.skillMdPath() == null || !Files.isRegularFile(skill.skillMdPath())) {
            findings.add(new Finding(Severity.ERROR, "SKILL.md 文件不存在或不可读"));
        } else {
            Path parent = skill.skillMdPath().getParent();
            if (parent != null && !parent.getFileName().toString().equals(skill.name())) {
                findings.add(new Finding(Severity.WARN,
                        "目录名 '" + parent.getFileName() + "' 与 name '" + skill.name() + "' 不一致（建议同名）"));
            }
        }

        if (skill.body() == null || skill.body().isBlank()) {
            findings.add(new Finding(Severity.ERROR, "SKILL.md 正文为空"));
        }

        for (String w : SkillQuality.validate(skill)) {
            findings.add(new Finding(Severity.WARN, w));
        }

        if (skill.referencesDir() != null) {
            Path index = skill.referencesDir().resolve("INDEX.md");
            if (!Files.isRegularFile(index)) {
                findings.add(new Finding(Severity.WARN,
                        "references/ 存在但缺少 INDEX.md（渐进加载建议先建索引）"));
            } else {
                findings.add(new Finding(Severity.INFO, "references/INDEX.md 已就绪"));
            }
        }

        if (!skill.dependencies().isEmpty()) {
            if (registry == null) {
                findings.add(new Finding(Severity.WARN, "无法校验 skill-dependencies（registry 未提供）"));
            } else {
                SkillDependencyLoader.Resolution resolution =
                        SkillDependencyLoader.resolve(skill, registry);
                for (String missing : resolution.missing()) {
                    findings.add(new Finding(Severity.ERROR, "依赖未找到或已禁用: " + missing));
                }
                for (String cycle : resolution.cycles()) {
                    findings.add(new Finding(Severity.ERROR, "依赖环或超深: " + cycle));
                }
                if (resolution.missing().isEmpty() && resolution.cycles().isEmpty()) {
                    findings.add(new Finding(Severity.INFO,
                            "依赖链 OK: " + resolution.loadOrder().stream()
                                    .map(Skill::name)
                                    .reduce((a, b) -> a + " → " + b)
                                    .orElse(skill.name())));
                }
            }
        }

        if (findings.isEmpty()) {
            findings.add(new Finding(Severity.INFO, "未发现问题"));
        }
        return new Report(skill.name(), List.copyOf(findings));
    }

    public static List<Report> checkAll(SkillRegistry registry) {
        List<Report> reports = new ArrayList<>();
        if (registry == null) {
            return reports;
        }
        for (Skill skill : registry.allSkills()) {
            reports.add(check(skill, registry));
        }
        return reports;
    }

    public static String format(Report report) {
        StringBuilder sb = new StringBuilder();
        String status = report.ok() ? "✅" : "❌";
        sb.append(status).append(' ').append(report.skillName())
                .append("（错误 ").append(report.errors())
                .append(" / 警告 ").append(report.warnings()).append("）\n");
        for (Finding f : report.findings()) {
            String mark = switch (f.severity()) {
                case ERROR -> "  ✗";
                case WARN -> "  ⚠";
                case INFO -> "  ·";
            };
            sb.append(mark).append(' ').append(f.message()).append('\n');
        }
        return sb.toString();
    }

    public static String formatAll(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return "📚 无可检查的 skill（先 /skill reload）";
        }
        long errors = reports.stream().mapToLong(Report::errors).sum();
        long warnings = reports.stream().mapToLong(Report::warnings).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Skill 检查：").append(reports.size()).append(" 个，")
                .append("错误 ").append(errors).append("，警告 ").append(warnings).append('\n');
        for (Report report : reports) {
            sb.append('\n').append(format(report));
        }
        return sb.toString().stripTrailing();
    }
}
