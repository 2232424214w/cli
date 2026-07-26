package com.bettercli.agent;

import com.bettercli.llm.LlmClient;
import com.bettercli.skill.SkillContextBuffer;
import com.bettercli.skill.SkillRegistry;
import com.bettercli.skill.SkillStateStore;
import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0：同一用户 turn 内 load_skill 后，第二次 LLM 调用必须已能看到 skill 正文。
 */
class AgentSameTurnSkillLoadTest {

    @TempDir
    Path tempDir;

    @Test
    void loadSkillBodyVisibleOnNextLlmCallInSameTurn() throws Exception {
        String oldMemoryDir = System.getProperty("bettercli.memory.dir");
        System.setProperty("bettercli.memory.dir", tempDir.resolve("mem").toString());
        try {
            Path skillMd = writeSkill(tempDir, "demo-skill",
                    "Demo skill for PDF tasks",
                    "# Demo Body\nUNIQUE_SKILL_TOKEN_ALPHA\nUse when handling PDF.");
            Path refs = skillMd.getParent().resolve("references");
            Files.createDirectories(refs);
            Files.writeString(refs.resolve("INDEX.md"), "| doc | summary |\n| --- | --- |\n| a.md | A |\n");

            SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
            SkillRegistry registry = new SkillRegistry(null, tempDir.resolve("user-skills"), null, state);
            registry.reload();

            SkillContextBuffer buffer = new SkillContextBuffer();
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            tools.setSkillRegistry(registry);
            tools.setSkillContextBuffer(buffer);

            ScriptedClient llm = new ScriptedClient();
            llm.enqueue(toolCall("load_skill", "{\"name\":\"demo-skill\"}"));
            llm.enqueue(new LlmClient.ChatResponse("assistant", "done with skill", null, 100, 20));

            Agent agent = new Agent(llm, tools);
            agent.setSkillRegistry(registry);
            agent.setSkillContextBuffer(buffer);

            String answer = agent.run("please handle my pdf");
            assertTrue(answer.contains("done with skill") || answer.isBlank(), answer);

            assertEquals(2, llm.capturedMessages.size(), "应有两轮 LLM 调用");
            List<LlmClient.Message> secondRound = llm.capturedMessages.get(1);
            assertTrue(secondRound.stream().anyMatch(m ->
                            m.content() != null && m.content().contains("UNIQUE_SKILL_TOKEN_ALPHA")),
                    "同轮第二次 LLM 调用必须已包含 skill 正文");
            assertTrue(secondRound.stream().anyMatch(m ->
                            m.content() != null
                                    && m.content().contains(SkillContextBuffer.INJECTION_HEADING_PREFIX)),
                    "注入段应带标准标题");
            assertTrue(buffer.isEmpty(), "flush 后 buffer 应为空");
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("bettercli.memory.dir");
            } else {
                System.setProperty("bettercli.memory.dir", oldMemoryDir);
            }
        }
    }

    private static Path writeSkill(Path tempDir, String name, String desc, String body) throws IOException {
        Path dir = tempDir.resolve("user-skills").resolve(name);
        Files.createDirectories(dir);
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: " + name + "\ndescription: " + desc + "\n---\n" + body + "\n");
        return skillMd;
    }

    private static LlmClient.ChatResponse toolCall(String name, String args) {
        return new LlmClient.ChatResponse(
                "assistant",
                null,
                List.of(new LlmClient.ToolCall("c1", new LlmClient.ToolCall.Function(name, args))),
                50,
                10
        );
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> capturedMessages = new ArrayList<>();

        void enqueue(ChatResponse response) {
            responses.add(response);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            capturedMessages.add(List.copyOf(messages));
            ChatResponse next = responses.poll();
            if (next == null) {
                throw new IOException("缺少预设响应");
            }
            return next;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 128_000;
        }

        @Override
        public boolean supportsTools() {
            return true;
        }
    }
}
