package com.bettercli.skill;

import com.bettercli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillDependencyLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesDependenciesBeforeMain() throws IOException {
        writeSkill("skill-sub", "当需要子步骤时使用。", "SUB_BODY", null);
        writeSkill("skill-main", "当需要主流程时使用。", "MAIN_BODY", """
                skill-dependencies:
                  - skill-sub
                """);
        SkillRegistry registry = registry();
        Skill main = registry.findSkill("skill-main");
        assertNotNull(main);
        assertEquals(List.of("skill-sub"), main.dependencies());

        SkillDependencyLoader.Resolution r = SkillDependencyLoader.resolve(main, registry);
        assertEquals(2, r.loadOrder().size());
        assertEquals("skill-sub", r.loadOrder().get(0).name());
        assertEquals("skill-main", r.loadOrder().get(1).name());
        assertTrue(r.missing().isEmpty());
        assertTrue(r.cycles().isEmpty());
    }

    @Test
    void loadSkillPushesDependenciesIntoBuffer() throws IOException {
        writeSkill("skill-sub", "当需要子步骤时使用。", "UNIQUE_SUB_TOKEN", null);
        writeSkill("skill-main", "当需要主流程时使用。", "UNIQUE_MAIN_TOKEN", """
                skill-dependencies:
                  - skill-sub
                """);
        SkillRegistry registry = registry();
        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);

        String ack = tools.executeTool("load_skill", "{\"name\":\"skill-main\"}");
        assertTrue(ack.contains("依赖装载顺序"), ack);
        assertTrue(ack.contains("skill-sub"), ack);

        String drained = buffer.drain();
        int subAt = drained.indexOf("UNIQUE_SUB_TOKEN");
        int mainAt = drained.indexOf("UNIQUE_MAIN_TOKEN");
        assertTrue(subAt >= 0 && mainAt >= 0, drained);
        assertTrue(subAt < mainAt, "依赖正文应排在主 skill 之前");
    }

    @Test
    void reportsMissingDependency() throws IOException {
        writeSkill("skill-main", "当需要主流程时使用。", "MAIN", """
                skill-dependencies:
                  - missing-dep
                """);
        SkillRegistry registry = registry();
        SkillDependencyLoader.Resolution r =
                SkillDependencyLoader.resolve(registry.findSkill("skill-main"), registry);
        assertTrue(r.missing().contains("missing-dep"));
        assertEquals(1, r.loadOrder().size());
    }

    @Test
    void parsesInlineDependencyArray() throws IOException {
        writeSkill("skill-sub", "当需要子步骤时使用。", "SUB", null);
        writeSkill("skill-main", "当需要主流程时使用。", "MAIN", "skill-dependencies: [skill-sub]\n");
        SkillRegistry registry = registry();
        assertEquals(List.of("skill-sub"), registry.findSkill("skill-main").dependencies());
    }

    private SkillRegistry registry() {
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, tempDir.resolve("user-skills"), null, state);
        registry.reload();
        return registry;
    }

    private void writeSkill(String name, String desc, String body, String extraFrontmatter) throws IOException {
        Path dir = tempDir.resolve("user-skills").resolve(name);
        Files.createDirectories(dir);
        StringBuilder fm = new StringBuilder();
        fm.append("---\nname: ").append(name).append('\n');
        fm.append("description: ").append(desc).append('\n');
        if (extraFrontmatter != null && !extraFrontmatter.isBlank()) {
            fm.append(extraFrontmatter);
            if (!extraFrontmatter.endsWith("\n")) {
                fm.append('\n');
            }
        }
        fm.append("---\n").append(body).append('\n');
        Files.writeString(dir.resolve("SKILL.md"), fm.toString());
    }
}
