package com.bettercli.skill;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillDraftGeneratorTest {

    @Test
    void heuristicDraftWhenNoLlm(@TempDir Path tempDir) throws Exception {
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("sys"),
                LlmClient.Message.user("请帮我整理发布检查清单"),
                LlmClient.Message.assistant("先列环境，再跑测试，最后打 tag。")
        );
        SkillDraftGenerator.DraftResult result =
                SkillDraftGenerator.generate(tempDir, "release-checklist", history, null);
        assertEquals("release-checklist", result.skillName());
        assertFalse(result.fromLlm());
        assertTrue(Files.isRegularFile(result.skillMd()));
        assertTrue(Files.readString(result.skillMd()).contains("name: release-checklist"));
        assertTrue(Files.readString(result.skillMd()).contains("发布检查"));
    }

    @Test
    void writesDraftFileIfSkillMdExists(@TempDir Path tempDir) throws Exception {
        SkillScaffold.create(tempDir, "existing-skill");
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.user("继续完善 existing skill")
        );
        SkillDraftGenerator.DraftResult result =
                SkillDraftGenerator.generate(tempDir, "existing-skill", history, null);
        assertTrue(result.skillMd().getFileName().toString().endsWith("SKILL.draft.md"));
    }

    @Test
    void summarizeSkipsSystemAndTool() {
        String s = SkillDraftGenerator.summarizeHistory(List.of(
                LlmClient.Message.system("ignore"),
                LlmClient.Message.user("hello world"),
                LlmClient.Message.tool("call-1", "tool out")
        ));
        assertTrue(s.contains("user:"));
        assertFalse(s.contains("ignore"));
        assertFalse(s.contains("tool out"));
    }
}
