package com.bettercli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsUserProjectAndLocalMemoryInOrder() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot.resolve(".bettercli"));
        Files.writeString(userDir.resolve("BETTER.md"), "- user rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");
        Files.writeString(projectRoot.resolve(".bettercli").resolve("BETTER.md"), "- dot project rule");
        Files.writeString(projectRoot.resolve("BETTER.local.md"), "- local rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("## BETTER.md 项目记忆"));
        assertTrue(context.indexOf("user rule") < context.indexOf("project rule"));
        assertTrue(context.indexOf("project rule") < context.indexOf("dot project rule"));
        assertTrue(context.indexOf("dot project rule") < context.indexOf("local rule"));
    }

    @Test
    void expandsRelativeImportsInsideAllowedRootOnly() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot.resolve("docs"));
        Files.writeString(projectRoot.resolve("docs").resolve("rules.md"), "- imported rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), """
                @docs/rules.md
                @../outside.md
                - root rule
                """);
        Files.writeString(tempDir.resolve("outside.md"), "- outside rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("- imported rule"));
        assertTrue(context.contains("- root rule"));
        assertFalse(context.contains("- outside rule"));
    }

    @Test
    void returnsEmptyContextWhenNoMemoryFilesExist() {
        String context = new ProjectMemoryLoader(tempDir.resolve("missing-user"), tempDir.resolve("missing-project"))
                .loadForPrompt();

        assertTrue(context.isEmpty());
    }

    @Test
    void recursiveDiscoveryWalksUpDirectoryTree() throws Exception {
        // 结构：tempDir/ancestor/parent/project
        // 在 ancestor 和 parent 各放一个 BETTER.md，验证向上递归加载
        Path userDir = tempDir.resolve("user");
        Path ancestor = tempDir.resolve("ancestor");
        Path parent = ancestor.resolve("parent");
        Path projectRoot = parent.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(ancestor.resolve("BETTER.md"), "- ancestor rule");
        Files.writeString(parent.resolve("BETTER.md"), "- parent rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot, true).loadForPrompt();

        assertTrue(context.contains("- ancestor rule"));
        assertTrue(context.contains("- parent rule"));
        assertTrue(context.contains("- project rule"));
        // 顺序：ancestor 在前，parent 在中，project 在后（从根到工作目录）
        assertTrue(context.indexOf("ancestor rule") < context.indexOf("parent rule"));
        assertTrue(context.indexOf("parent rule") < context.indexOf("project rule"));
    }

    @Test
    void recursiveDiscoveryDisabledByDefault() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path ancestor = tempDir.resolve("ancestor");
        Path parent = ancestor.resolve("parent");
        Path projectRoot = parent.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(ancestor.resolve("BETTER.md"), "- ancestor rule");
        Files.writeString(parent.resolve("BETTER.md"), "- parent rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");

        // 默认不启用向上递归
        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("- project rule"));
        assertFalse(context.contains("- ancestor rule"));
        assertFalse(context.contains("- parent rule"));
    }

    @Test
    void recursiveDiscoveryDeduplicatesSources() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path parent = tempDir.resolve("parent");
        Path projectRoot = parent.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(parent.resolve("BETTER.md"), "- parent rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot, true).loadForPrompt();

        // project rule 只应出现一次（去重）
        long projectRuleCount = context.lines().filter(l -> l.contains("- project rule")).count();
        assertEquals(1, projectRuleCount);
    }

    @Test
    void capacityManagementApiReturnsCorrectCounts() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(userDir.resolve("BETTER.md"), "- user rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule with some content");

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot, false, 2200, 0.8);

        assertTrue(loader.getCharCount() > 0);
        assertEquals(2200, loader.getMaxChars());
        assertEquals(0.8, loader.getIntegrateThreshold(), 0.001);
        assertFalse(loader.isOverThreshold());
        assertFalse(loader.isOverLimit());
    }

    @Test
    void capacityStatusReportsOverThreshold() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        // 写入 1800 字符，超过 80% 阈值（2200 * 0.8 = 1760）
        StringBuilder content = new StringBuilder();
        content.append("- ".repeat(900));  // 约 1800 字符
        Files.writeString(projectRoot.resolve("BETTER.md"), content.toString());

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot, false, 2200, 0.8);

        assertTrue(loader.isOverThreshold());
        String status = loader.getCapacityStatus();
        assertNotNull(status);
        assertTrue(status.contains("已超过"));
        assertTrue(status.contains("阈值"));
    }

    @Test
    void capacityStatusReportsOverLimit() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        // 写入 2500 字符，超过 2200 上限
        StringBuilder content = new StringBuilder();
        content.append("- ".repeat(1250));  // 约 2500 字符
        Files.writeString(projectRoot.resolve("BETTER.md"), content.toString());

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot, false, 2200, 0.8);

        assertTrue(loader.isOverLimit());
        String status = loader.getCapacityStatus();
        assertTrue(status.contains("已超过上限"));
    }

    @Test
    void capacityStatusReportsHealthyWhenUnderThreshold() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("BETTER.md"), "- short rule");

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot, false, 2200, 0.8);

        assertFalse(loader.isOverThreshold());
        String status = loader.getCapacityStatus();
        assertTrue(status.contains("容量充足"));
    }

    @Test
    void readContentReturnsAllLoadedFiles() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(userDir.resolve("BETTER.md"), "- user rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot);

        String content = loader.readContent();
        assertTrue(content.contains("- user rule"));
        assertTrue(content.contains("- project rule"));
    }

    @Test
    void getLoadedFilesReturnsExistingFiles() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(userDir.resolve("BETTER.md"), "- user rule");
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot);

        assertEquals(2, loader.getLoadedFiles().size());
    }

    @Test
    void getSuggestTargetReturnsProjectPaiMdWhenExists() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("BETTER.md"), "- project rule");

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot);

        Path target = loader.getSuggestTarget();
        assertEquals(projectRoot.resolve("BETTER.md").toAbsolutePath().normalize(),
                target.toAbsolutePath().normalize());
    }

    @Test
    void getSuggestTargetDefaultsToProjectPaiMdWhenNoneExists() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot);

        ProjectMemoryLoader loader = new ProjectMemoryLoader(userDir, projectRoot);

        Path target = loader.getSuggestTarget();
        assertEquals(projectRoot.resolve("BETTER.md").toAbsolutePath().normalize(),
                target.toAbsolutePath().normalize());
    }
}
