package com.bettercli.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentScaffoldTest {

    @TempDir
    Path temp;

    @Test
    void parseCreatePayloadDefaults() {
        var req = CustomSubAgentScaffold.parseCreatePayload("my-agent");
        assertEquals("my-agent", req.name());
        assertEquals(CustomSubAgentScaffold.Scope.PROJECT, req.scope());
        assertEquals("blank", req.templateId());
        assertFalse(req.force());
    }

    @Test
    void parseCreatePayloadFlags() {
        var req = CustomSubAgentScaffold.parseCreatePayload(
                "sql-analyzer --user --template researcher --force");
        assertEquals("sql-analyzer", req.name());
        assertEquals(CustomSubAgentScaffold.Scope.USER, req.scope());
        assertEquals("researcher", req.templateId());
        assertTrue(req.force());
    }

    @Test
    void parseRejectsUnknownFlagAndExtraName() {
        assertThrows(IllegalArgumentException.class,
                () -> CustomSubAgentScaffold.parseCreatePayload("a --nope"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomSubAgentScaffold.parseCreatePayload("a b"));
        assertThrows(IllegalArgumentException.class,
                () -> CustomSubAgentScaffold.parseCreatePayload(""));
    }

    @Test
    void createBlankUnderProjectAndReload(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user-agents");
        Path project = dir.resolve("project-agents");
        var result = CustomSubAgentScaffold.create(
                new CustomSubAgentScaffold.CreateRequest("helper", CustomSubAgentScaffold.Scope.PROJECT,
                        "blank", false),
                user, project);
        assertTrue(Files.isRegularFile(result.agentMd()));
        assertTrue(Files.isRegularFile(result.agentDir().resolve("MEMORY.md")));
        String md = Files.readString(result.agentMd());
        assertTrue(md.contains("name: helper"));
        assertTrue(md.contains("请改成一句话能力说明"));

        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, project);
        registry.reload();
        assertNotNull(registry.find("helper"));
    }

    @Test
    void createCodeReviewerAndRefuseOverwriteWithoutForce() throws Exception {
        Path user = temp.resolve("u");
        Path project = temp.resolve("p");
        CustomSubAgentScaffold.create(
                new CustomSubAgentScaffold.CreateRequest("reviewer", CustomSubAgentScaffold.Scope.USER,
                        "code-reviewer", false),
                user, project);
        String md = Files.readString(user.resolve("reviewer/AGENT.md"));
        assertTrue(md.contains("只读代码审查"));
        assertTrue(md.contains("allowedTools"));

        assertThrows(IllegalStateException.class, () -> CustomSubAgentScaffold.create(
                new CustomSubAgentScaffold.CreateRequest("reviewer", CustomSubAgentScaffold.Scope.USER,
                        "blank", false),
                user, project));

        CustomSubAgentScaffold.create(
                new CustomSubAgentScaffold.CreateRequest("reviewer", CustomSubAgentScaffold.Scope.USER,
                        "blank", true),
                user, project);
        assertTrue(Files.readString(user.resolve("reviewer/AGENT.md")).contains("请改成一句话能力说明"));
    }

    @Test
    void deleteRequiresForceAndRemovesDir() throws Exception {
        Path user = temp.resolve("u");
        Path project = temp.resolve("p");
        CustomSubAgentScaffold.create(
                new CustomSubAgentScaffold.CreateRequest("tmp-bot", CustomSubAgentScaffold.Scope.PROJECT,
                        "blank", false),
                user, project);
        assertTrue(Files.isDirectory(project.resolve("tmp-bot")));
        assertThrows(IllegalArgumentException.class, () ->
                CustomSubAgentScaffold.delete("tmp-bot", false, false, user, project, null));
        String msg = CustomSubAgentScaffold.delete("tmp-bot", true, false, user, project, null);
        assertTrue(msg.contains("已删除"));
        assertFalse(Files.exists(project.resolve("tmp-bot")));
    }

    @Test
    void rejectsInvalidName() {
        assertFalse(CustomSubAgentScaffold.isValidName("1bad"));
        assertFalse(CustomSubAgentScaffold.isValidName("../x"));
        assertTrue(CustomSubAgentScaffold.isValidName("code-reviewer"));
    }
}
