package com.bettercli.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentRegistryTest {

    @Test
    void loadsFromUserAndProjectWithProjectOverride(@TempDir Path tempDir) throws IOException {
        Path builtin = tempDir.resolve("builtin");
        Path user = tempDir.resolve("user");
        Path project = tempDir.resolve("project");
        writeAgent(builtin, "code-reviewer", "builtin desc", "builtin body");
        writeAgent(user, "sql-analyzer", "user sql", "sql body");
        writeAgent(project, "code-reviewer", "project desc", "project body");

        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(builtin, user, project);
        registry.reload();

        assertEquals(2, registry.all().size());
        CustomSubAgentDefinition reviewer = registry.find("code-reviewer");
        assertNotNull(reviewer);
        assertEquals("project desc", reviewer.description());
        assertEquals(CustomSubAgentDefinition.Source.PROJECT, reviewer.source());
        assertTrue(reviewer.body().contains("project body"));

        CustomSubAgentDefinition sql = registry.find("sql-analyzer");
        assertNotNull(sql);
        assertEquals(CustomSubAgentDefinition.Source.USER, sql.source());
    }

    @Test
    void fallsBackToDirectoryNameWhenNameMissing(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        Path dir = user.resolve("my-agent");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"),
                "---\ndescription: no name field\n---\nbody\n");

        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();

        assertNotNull(registry.find("my-agent"));
    }

    @Test
    void loadsMemoryMdAndToolLists(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        Path dir = user.resolve("sql-analyzer");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), """
                ---
                name: sql-analyzer
                description: slow sql
                model: deepseek
                maxTurns: 12
                timeoutSeconds: 90
                allowedTools: [read_file, grep_code]
                disallowedTools: [execute_command]
                skills: [web-access]
                ---
                you are sql expert
                """);
        Files.writeString(dir.resolve("MEMORY.md"), "prefer EXPLAIN ANALYZE\n");

        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();

        CustomSubAgentDefinition def = registry.find("sql-analyzer");
        assertNotNull(def);
        assertEquals("deepseek", def.model());
        assertEquals(12, def.maxTurns());
        assertEquals(90, def.timeoutSeconds());
        assertEquals(90, def.resolveTimeoutSeconds());
        assertEquals(List.of("read_file", "grep_code"), def.allowedTools());
        assertEquals(List.of("execute_command"), def.disallowedTools());
        assertEquals(List.of("web-access"), def.skills());
        assertTrue(def.memoryMd().contains("EXPLAIN"));
        assertNotNull(def.memoryFilePath());

        Set<String> effective = def.resolveEffectiveTools(
                Set.of("read_file", "grep_code", "execute_command", "run_subagent", "write_file",
                        "load_skill", "write_subagent_memory"));
        assertTrue(effective.contains("read_file"));
        assertTrue(effective.contains("grep_code"));
        assertTrue(effective.contains("load_skill"));
        assertTrue(effective.contains("write_subagent_memory"));
        assertFalse(effective.contains("run_subagent"));
        assertFalse(effective.contains("execute_command"));
    }

    @Test
    void loadsSoulAndIdentityIntoCompose(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        Path dir = user.resolve("reviewer");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), """
                ---
                name: reviewer
                description: review
                ---
                agent body
                """);
        Files.writeString(dir.resolve("SOUL.md"), "be careful and precise\n");
        Files.writeString(dir.resolve("IDENTITY.md"), "you are a senior reviewer\n");

        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();
        CustomSubAgentDefinition def = registry.find("reviewer");
        assertNotNull(def);
        assertTrue(def.soulMd().contains("careful"));
        assertTrue(def.identityMd().contains("senior"));
        String core = def.composeSystemPromptCore();
        assertTrue(core.contains("agent body"));
        assertTrue(core.contains("## Soul"));
        assertTrue(core.contains("## Identity"));
    }

    @Test
    void runtimeContextSkillWhitelist() {
        CustomSubAgentRuntimeContext open = new CustomSubAgentRuntimeContext("a", null, List.of());
        assertTrue(open.allowsSkill("web-access"));
        CustomSubAgentRuntimeContext limited = new CustomSubAgentRuntimeContext(
                "a", null, List.of("web-access"));
        assertTrue(limited.allowsSkill("web-access"));
        assertFalse(limited.allowsSkill("other"));
    }

    @Test
    void emptyAllowedToolsMeansAllMinusDisallowedAndRecursive(@TempDir Path tempDir) {
        CustomSubAgentDefinition def = new CustomSubAgentDefinition(
                "x", "d", "body", null, null, null,
                List.of(), List.of("write_file"), List.of(), "",
                "", "",
                CustomSubAgentDefinition.Source.USER, null, null);
        Set<String> effective = def.resolveEffectiveTools(
                Set.of("read_file", "write_file", "run_subagent", "run_team"));
        assertEquals(Set.of("read_file"), effective);
    }

    @Test
    void inheritsFromBaseAgent(@TempDir Path tempDir) throws IOException {
        Path user = tempDir.resolve("user");
        writeAgent(user, "code-reviewer", "base review", "base body");
        Path child = user.resolve("strict-reviewer");
        Files.createDirectories(child);
        Files.writeString(child.resolve("AGENT.md"), """
                ---
                name: strict-reviewer
                from: code-reviewer
                description: stricter review
                ---
                """);

        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(null, user, null);
        registry.reload();
        CustomSubAgentDefinition def = registry.find("strict-reviewer");
        assertNotNull(def);
        assertEquals("stricter review", def.description());
        assertTrue(def.body().contains("base body"));
        assertEquals("code-reviewer", def.extendsFrom());
    }

    private static void writeAgent(Path root, String name, String desc, String body) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"),
                "---\nname: " + name + "\ndescription: " + desc + "\n---\n" + body + "\n");
    }
}
