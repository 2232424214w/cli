package com.bettercli.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 渐进式披露相关的质量护栏与 references 协议辅助（对标 1024 Agent Skills 指南）。
 *
 * <ul>
 *   <li>name：小写字母/数字/连字符，≤64</li>
 *   <li>description：软性提示（过短、第一人称、缺触发语境）</li>
 *   <li>body：软上限约 500 行 / 5k 词，超限警告不阻断加载</li>
 *   <li>INDEX.md：截取短摘要嵌入 load_skill 确认，鼓励按需 read_file</li>
 * </ul>
 */
public final class SkillQuality {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int SOFT_BODY_MAX_LINES = 500;
    public static final int SOFT_BODY_MAX_WORDS = 5_000;
    public static final int INDEX_EXCERPT_MAX_CHARS = 1_200;
    public static final int INDEX_LISTING_MAX_FILES = 12;

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private SkillQuality() {
    }

    public static boolean isValidName(String name) {
        return name != null
                && !name.isBlank()
                && name.length() <= MAX_NAME_LENGTH
                && NAME_PATTERN.matcher(name).matches();
    }

    /** 加载期软性质量警告（不阻断）。 */
    public static List<String> validate(Skill skill) {
        List<String> warnings = new ArrayList<>();
        if (skill == null) {
            return warnings;
        }
        if (!isValidName(skill.name())) {
            warnings.add("name '" + skill.name()
                    + "' 建议使用 kebab-case（小写字母/数字/连字符，≤"
                    + MAX_NAME_LENGTH + "），对标 1024 Skills 命名约定");
        }
        String desc = skill.description() == null ? "" : skill.description().trim();
        if (desc.isBlank()) {
            warnings.add("description 为空：索引无法触发发现，请写清功能与使用时机");
        } else {
            if (desc.length() < 20) {
                warnings.add("description 过短：建议包含功能 + 触发场景关键词");
            }
            String lower = desc.toLowerCase(Locale.ROOT);
            if (desc.contains("我 ") || desc.contains("你 ") || desc.contains("我们")
                    || lower.startsWith("i ") || lower.contains(" you ")) {
                warnings.add("description 建议用第三人称（功能/时机），避免「我/你」口吻");
            }
            if (!desc.contains("时") && !desc.contains("当") && !lower.contains("when")
                    && !lower.contains("use ") && !desc.contains("触发")) {
                warnings.add("description 建议写明触发时机（如「当用户…时使用」）");
            }
        }
        String body = skill.body() == null ? "" : skill.body();
        int lines = body.isEmpty() ? 0 : body.split("\\R", -1).length;
        int words = estimateWordCount(body);
        if (lines > SOFT_BODY_MAX_LINES || words > SOFT_BODY_MAX_WORDS) {
            warnings.add("SKILL.md 正文约 " + lines + " 行 / " + words
                    + " 词，超过建议软上限（" + SOFT_BODY_MAX_LINES + " 行 / "
                    + SOFT_BODY_MAX_WORDS + " 词）；请拆到 references/ 并保留一级链接");
        }
        return warnings;
    }

    /**
     * 为 load_skill 确认消息附加 references 协议段：目录、INDEX 摘要或文件清单。
     */
    public static String formatReferencesGuide(Skill skill) {
        if (skill == null || skill.referencesDir() == null) {
            return "";
        }
        Path abs = skill.referencesDir().toAbsolutePath().normalize();
        StringBuilder sb = new StringBuilder();
        sb.append("\nreferences 目录: ").append(abs);
        Path index = abs.resolve("INDEX.md");
        if (Files.isRegularFile(index)) {
            sb.append("\n建议先 read_file \"").append(index)
                    .append("\"，再按 INDEX 按需加载其它文档，勿一次读入全部。");
            String excerpt = readIndexExcerpt(index);
            if (!excerpt.isBlank()) {
                sb.append("\n--- INDEX.md 摘要 ---\n").append(excerpt);
                if (!excerpt.endsWith("\n")) {
                    sb.append('\n');
                }
                sb.append("---");
            }
        } else {
            sb.append("\n未找到 INDEX.md；可按需 read_file 下列参考文档，勿一次读入全部。");
            List<String> listing = listReferenceFiles(abs);
            if (!listing.isEmpty()) {
                sb.append('\n');
                for (String rel : listing) {
                    sb.append("- ").append(rel).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** body 超软上限时给 load_skill 确认追加一行提示。 */
    public static String formatBodySizeHint(Skill skill) {
        if (skill == null) {
            return "";
        }
        String body = skill.body() == null ? "" : skill.body();
        int lines = body.isEmpty() ? 0 : body.split("\\R", -1).length;
        int words = estimateWordCount(body);
        if (lines <= SOFT_BODY_MAX_LINES && words <= SOFT_BODY_MAX_WORDS) {
            return "";
        }
        return "\n⚠️ 正文偏长（约 " + lines + " 行 / " + words
                + " 词）。优先按 SKILL.md 内链接 read_file references，避免把大段参考贴进对话。";
    }

    static String readIndexExcerpt(Path indexFile) {
        try {
            String raw = Files.readString(indexFile, StandardCharsets.UTF_8).strip();
            if (raw.isEmpty()) {
                return "";
            }
            if (raw.length() <= INDEX_EXCERPT_MAX_CHARS) {
                return raw;
            }
            return raw.substring(0, INDEX_EXCERPT_MAX_CHARS) + "\n...(INDEX 已截断，完整内容请 read_file)";
        } catch (IOException e) {
            return "";
        }
    }

    static List<String> listReferenceFiles(Path refsDir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(refsDir, 3)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".md") || n.endsWith(".txt");
                    })
                    .sorted()
                    .limit(INDEX_LISTING_MAX_FILES)
                    .forEach(p -> out.add(refsDir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException ignored) {
            // best-effort
        }
        return out;
    }

    static int estimateWordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // 中文按字近似、英文按空白分词，取较大者作软阈值
        int cjk = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                cjk++;
            }
            i += Character.charCount(cp);
        }
        String[] latin = text.trim().split("\\s+");
        int latinWords = text.isBlank() ? 0 : latin.length;
        return Math.max(cjk, latinWords);
    }
}
