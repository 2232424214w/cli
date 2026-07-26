package com.bettercli.skill;

import com.bettercli.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从当前会话历史草稿化 SKILL.md（对标 1024「Skill 生成」CLI 降级版）。
 *
 * <p>优先调用 LLM 生成；失败或未注入 client 时回退为启发式模板。
 */
public final class SkillDraftGenerator {

    private static final int MAX_TRANSCRIPT_CHARS = 12_000;
    private static final Pattern FENCE = Pattern.compile(
            "```(?:markdown|md)?\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    public record DraftResult(String skillName, Path skillMd, String content, boolean fromLlm) {
    }

    private SkillDraftGenerator() {
    }

    public static DraftResult generate(Path skillsRoot,
                                       String requestedName,
                                       List<LlmClient.Message> history,
                                       LlmClient llmClient) throws IOException {
        if (skillsRoot == null) {
            throw new IllegalArgumentException("skills 根目录不能为空");
        }
        String transcript = summarizeHistory(history);
        if (transcript.isBlank()) {
            throw new IllegalArgumentException("当前会话没有可用于生成的对话内容");
        }

        String name = requestedName;
        if (name == null || name.isBlank()) {
            name = guessName(transcript);
        }
        if (!SkillQuality.isValidName(name)) {
            name = "session-skill";
        }

        String content = null;
        boolean fromLlm = false;
        if (llmClient != null) {
            try {
                content = askLlm(llmClient, name, transcript);
                fromLlm = content != null && content.contains("---");
            } catch (Exception ignored) {
                fromLlm = false;
            }
        }
        if (!fromLlm) {
            content = heuristicDraft(name, transcript);
            fromLlm = false;
        }

        Path dir = skillsRoot.resolve(name);
        Files.createDirectories(dir);
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.exists(skillMd)) {
            skillMd = dir.resolve("SKILL.draft.md");
        }
        Files.writeString(skillMd, content, StandardCharsets.UTF_8);
        Path refs = dir.resolve("references");
        if (!Files.isDirectory(refs)) {
            Files.createDirectories(refs);
            Files.writeString(refs.resolve("INDEX.md"),
                    SkillScaffold.indexTemplate(name), StandardCharsets.UTF_8);
        }
        return new DraftResult(name, skillMd.toAbsolutePath().normalize(), content, fromLlm);
    }

    static String summarizeHistory(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message msg : history) {
            if (msg == null || msg.role() == null) {
                continue;
            }
            String role = msg.role().toLowerCase(Locale.ROOT);
            if ("system".equals(role) || "tool".equals(role)) {
                continue;
            }
            String text = msg.content() == null ? "" : msg.content().strip();
            if (text.isBlank()) {
                continue;
            }
            if (text.length() > 800) {
                text = text.substring(0, 800) + "...";
            }
            sb.append(role).append(": ").append(text).append("\n\n");
            if (sb.length() >= MAX_TRANSCRIPT_CHARS) {
                break;
            }
        }
        if (sb.length() > MAX_TRANSCRIPT_CHARS) {
            return sb.substring(0, MAX_TRANSCRIPT_CHARS);
        }
        return sb.toString().strip();
    }

    static String guessName(String transcript) {
        // 取首条用户消息的前几个实词拼 kebab
        for (String line : transcript.split("\\R")) {
            if (!line.startsWith("user:")) {
                continue;
            }
            String body = line.substring(5).trim().toLowerCase(Locale.ROOT);
            List<String> tokens = new ArrayList<>();
            Matcher m = Pattern.compile("[a-z0-9\\u4e00-\\u9fff]{2,}").matcher(body);
            while (m.find() && tokens.size() < 4) {
                String t = m.group();
                if (t.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                    // 中文取前 4 字拼音化太重，改用 session + 序号友好名
                    return "session-skill";
                }
                tokens.add(t.replaceAll("[^a-z0-9]+", ""));
            }
            if (!tokens.isEmpty()) {
                String joined = String.join("-", tokens).replaceAll("-{2,}", "-");
                if (SkillQuality.isValidName(joined)) {
                    return joined;
                }
            }
            break;
        }
        return "session-skill";
    }

    private static String askLlm(LlmClient client, String name, String transcript) throws IOException {
        String prompt = """
                你是 Skill 作者。根据下列会话摘录，输出一份完整的 SKILL.md（含 YAML frontmatter）。
                要求：
                1. name 必须是：%s
                2. description 用第三人称，写清功能与触发时机（「当用户…时」）
                3. 正文是决策手册，不是系统提示词复读；控制在约 80 行内
                4. 提到需要细节时引导 read_file references/
                5. 只输出 Markdown 文件内容，不要解释

                --- 会话摘录 ---
                %s
                """.formatted(name, transcript);
        LlmClient.ChatResponse response = client.chat(
                List.of(LlmClient.Message.user(prompt)),
                null);
        String raw = response == null || response.content() == null ? "" : response.content().strip();
        if (raw.isBlank()) {
            return null;
        }
        Matcher fence = FENCE.matcher(raw);
        if (fence.find()) {
            raw = fence.group(1).strip();
        }
        if (!raw.contains("---")) {
            return null;
        }
        return raw.endsWith("\n") ? raw : raw + "\n";
    }

    static String heuristicDraft(String name, String transcript) {
        String excerpt = transcript.length() > 1500 ? transcript.substring(0, 1500) + "\n..." : transcript;
        return """
                ---
                name: %s
                description: |
                  当用户继续处理与本会话相似的任务时使用。
                  由 /skill draft 根据会话摘录自动生成，请人工校对后再依赖。
                version: "0.1.0"
                author: session-draft
                tags: [draft, session]
                ---

                # %s

                ## 来源

                本 skill 由当前会话草稿生成（启发式，未调用或未成功调用 LLM）。请编辑后删除草稿痕迹。

                ## 会话摘录（供改写）

                ```
                %s
                ```

                ## 建议决策步骤

                1. 对照摘录提炼可复用流程
                2. 把细节挪到 `references/`，本文件只留决策与触发条件
                3. `/skill check %s` 核查质量后再 `/skill on %s`
                """.formatted(name, name, excerpt, name, name);
    }
}
