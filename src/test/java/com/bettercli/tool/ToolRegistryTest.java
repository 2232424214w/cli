package com.bettercli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bettercli.browser.BrowserConnector;
import com.bettercli.mcp.protocol.McpToolDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldExposePartialWhenGrepReachesHeadLimit(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("bettercli.search.disable.rg");
        System.setProperty("bettercli.search.disable.rg", "true");
        try {
            Files.writeString(tempDir.resolve("Many.java"), String.join("\n",
                    "class Many {",
                    "  String first = \"needle\";",
                    "  String second = \"needle\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"head_limit\":1,\"max_results\":10}");

            assertTrue(result.contains("Many.java:2"));
            assertTrue(!result.contains("Many.java:3"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("head_limit=1"));
            assertTrue(result.contains("suggested_reads"));
            assertTrue(result.contains("read_file {\"path\":\"Many.java\""));
        } finally {
            restoreSystemProperty("bettercli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldExposePartialWhenGrepResultReachesCharacterBudget(@TempDir Path tempDir) throws Exception {
        String previous = System.getProperty("bettercli.search.disable.rg");
        System.setProperty("bettercli.search.disable.rg", "true");
        try {
            String longNeedleLine = "needle " + "x".repeat(1200);
            Files.writeString(tempDir.resolve("Budget.java"), String.join("\n",
                    "class Budget {",
                    "  String first = \"" + longNeedleLine + "\";",
                    "  String second = \"" + longNeedleLine + "\";",
                    "}"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());

            String result = registry.executeTool("grep_code",
                    "{\"pattern\":\"needle\",\"max_results\":10,\"max_chars\":1000}");

            assertTrue(result.contains("Budget.java:2"));
            assertTrue(result.contains("partial: true"));
            assertTrue(result.contains("max_chars=1000"));
        } finally {
            restoreSystemProperty("bettercli.search.disable.rg", previous);
        }
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldRouteWebSearchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"},
                    "top_k": {"type": "integer"}
                  }
                }
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\",\"top_k\":3}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-result"));
        assertTrue(result.contains("\"query\":\"Step 3.7 Flash\""));
        assertTrue(result.contains("\"top_k\":3"));
    }

    @Test
    void shouldRouteWebFetchThroughStepSearchMcpForStep37Flash() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.7-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_fetch", """
                {
                  "type": "object",
                  "properties": {
                    "url": {"type": "string"},
                    "max_chars": {"type": "integer"}
                  }
                }
                """), args -> "step-fetch:" + args);

        String result = registry.executeTool("web_fetch",
                "{\"url\":\"https://platform.stepfun.com/docs/zh/step-plan/integrations/search-mcp\",\"max_chars\":1200}");

        assertTrue(result.contains("[StepSearch]"));
        assertTrue(result.contains("step-fetch"));
        assertTrue(result.contains("\"url\":\"https://platform.stepfun.com/docs/zh/step-plan/integrations/search-mcp\""));
        assertTrue(result.contains("\"max_chars\":1200"));
    }

    @Test
    void shouldNotRouteStepSearchForOlderStepModel() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setCurrentModel("step", "step-3.5-flash");
        registry.registerMcpTool(stepSearchDescriptor("web_search", """
                {"type": "object", "properties": {"query": {"type": "string"}}}
                """), args -> "step-result:" + args);

        String result = registry.executeTool("web_search", "{\"query\":\"Step 3.7 Flash\"}");

        assertFalse(result.contains("step-result"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    private static McpToolDescriptor stepSearchDescriptor(String name, String schema) throws Exception {
        JsonNode inputSchema = MAPPER.readTree(schema);
        return new McpToolDescriptor(
                "step_search",
                name,
                "mcp__step_search__" + name,
                "StepSearch " + name,
                inputSchema);
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }

    @Test
    void readPaiMdToolReturnsContentAndCapacityStatus(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("BETTER.md"), "# 项目记忆\n\n- 使用 Java 17\n- 测试框架 JUnit 5");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_better_md", "{}");

        assertTrue(result.contains("BETTER.md 容量:"), "应包含容量状态");
        assertTrue(result.contains("--- BETTER.md 内容 ---"), "应包含内容分隔标记");
        assertTrue(result.contains("使用 Java 17"), "应包含 BETTER.md 实际内容");
        assertTrue(result.contains("已加载 BETTER.md 文件"), "应包含已加载文件列表");
    }

    @Test
    void readPaiMdToolReturnsOnlySummaryWhenSummaryTrue(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("BETTER.md"), "# 项目记忆\n\n- 使用 Java 17\n- 测试框架 JUnit 5");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_better_md", "{\"summary\":true}");

        assertTrue(result.contains("BETTER.md 容量:"), "summary 模式应包含容量状态");
        assertFalse(result.contains("--- BETTER.md 内容 ---"), "summary 模式不应包含完整内容");
        assertFalse(result.contains("使用 Java 17"), "summary 模式不应包含 BETTER.md 实际内容");
    }

    @Test
    void readPaiMdToolReportsEmptyWhenNoPaiMd(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_better_md", "{}");

        assertTrue(result.contains("BETTER.md 容量:"), "无 BETTER.md 时也应返回容量状态");
        assertTrue(result.contains("当前未加载任何 BETTER.md 文件"), "应明确提示未加载任何文件");
    }

    @Test
    void readPaiMdToolReportsOverThreshold(@TempDir Path tempDir) throws Exception {
        // 写一个超过 80% 阈值但未超过上限的 BETTER.md（默认 2200 字符，阈值 80% 即 1760）
        StringBuilder content = new StringBuilder("# 项目记忆\n\n");
        while (content.length() < 1800) {
            content.append("- 规则条目 ").append(content.length()).append("\n");
        }
        Files.writeString(tempDir.resolve("BETTER.md"), content.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_better_md", "{\"summary\":true}");

        assertTrue(result.contains("BETTER.md 容量:"), "应返回容量状态");
        assertTrue(result.contains("已超过 80% 阈值"), "应提示已超过整合阈值");
    }

    @Test
    void readPaiMdToolReportsOverLimit(@TempDir Path tempDir) throws Exception {
        // 写一个超过 2200 字符上限的 BETTER.md
        StringBuilder content = new StringBuilder("# 项目记忆\n\n");
        while (content.length() < 2300) {
            content.append("- 规则条目 ").append(content.length()).append("\n");
        }
        Files.writeString(tempDir.resolve("BETTER.md"), content.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_better_md", "{\"summary\":true}");

        assertTrue(result.contains("BETTER.md 容量:"), "应返回容量状态");
        assertTrue(result.contains("已超过上限"), "应提示已超过字符上限");
    }

    @Test
    void readPaiMdToolUsesInjectedProjectMemoryLoader(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("BETTER.md"), "- 注入的 BETTER.md 内容");
        Path userDir = tempDir.resolve("user");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("BETTER.md"), "- 注入的用户级 BETTER.md");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectMemoryLoader(new com.bettercli.prompt.ProjectMemoryLoader(userDir, tempDir, false));

        String result = registry.executeTool("read_better_md", "{}");

        assertTrue(result.contains("注入的 BETTER.md 内容"), "应包含项目级 BETTER.md 内容");
        assertTrue(result.contains("注入的用户级 BETTER.md"), "应包含用户级 BETTER.md 内容");
    }

    @Test
    void suggestPaiMdToolAppendsToExistingFile(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("BETTER.md"), "# BETTER.md\n\n- 已有规则\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("suggest_better_md",
                "{\"suggestion\":\"- 新规则：使用 Java 17\",\"reason\":\"用户要求记录\"}");

        assertTrue(result.contains("已追加到 BETTER.md"), "应提示已追加成功");
        assertTrue(result.contains("BETTER.md 容量:"), "应返回新的容量状态");
        String written = Files.readString(tempDir.resolve("BETTER.md"));
        assertTrue(written.contains("已有规则"), "应保留原有内容");
        assertTrue(written.contains("- 新规则：使用 Java 17"), "应追加新条目");
    }

    @Test
    void suggestPaiMdToolCreatesNewFileWhenMissing(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("suggest_better_md",
                "{\"suggestion\":\"- 首条规则\"}");

        assertTrue(result.contains("已追加到 BETTER.md"), "应提示已追加成功");
        assertTrue(Files.exists(tempDir.resolve("BETTER.md")), "应创建 BETTER.md 文件");
        String written = Files.readString(tempDir.resolve("BETTER.md"));
        assertTrue(written.contains("# BETTER.md"), "新文件应包含 BETTER.md 头部");
        assertTrue(written.contains("- 首条规则"), "应包含新条目");
    }

    @Test
    void suggestPaiMdToolRejectsWhenOverLimit(@TempDir Path tempDir) throws Exception {
        StringBuilder content = new StringBuilder("# BETTER.md\n\n");
        while (content.length() < 2300) {
            content.append("- 占位规则 ").append(content.length()).append("\n");
        }
        Files.writeString(tempDir.resolve("BETTER.md"), content.toString());
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("suggest_better_md",
                "{\"suggestion\":\"- 超限后的新规则\"}");

        assertTrue(result.contains("suggest_better_md 失败"), "超上限应拒绝");
        assertTrue(result.contains("已超过上限"), "应提示容量已超上限");
        assertTrue(result.contains("整合"), "应建议先整合");
    }

    @Test
    void suggestPaiMdToolRejectsEmptySuggestion(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("suggest_better_md", "{\"suggestion\":\"\"}");

        assertTrue(result.contains("suggest_better_md 失败"), "空建议应拒绝");
        assertTrue(result.contains("suggestion 不能为空"), "应明确提示 suggestion 不能为空");
    }

    @Test
    void suggestPaiMdToolWritesToExplicitTarget(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".bettercli"));
        Files.writeString(tempDir.resolve(".bettercli").resolve("BETTER.md"), "# BETTER.md\n\n- 已有\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("suggest_better_md",
                "{\"suggestion\":\"- 新规则\",\"target\":\".bettercli/BETTER.md\"}");

        assertTrue(result.contains("已追加到 BETTER.md"), "应提示已追加成功");
        String written = Files.readString(tempDir.resolve(".bettercli").resolve("BETTER.md"));
        assertTrue(written.contains("- 新规则"), "应写入到显式指定的目标文件");
    }

    @Test
    void suggestPaiMdToolIsRegisteredAsDangerousTool() {
        assertTrue(com.bettercli.hitl.ApprovalPolicy.requiresApproval("suggest_better_md"),
                "suggest_better_md 应被 ApprovalPolicy 标记为需要 HITL 审批");
        assertTrue(com.bettercli.hitl.ApprovalPolicy.getDangerousTools().contains("suggest_better_md"),
                "suggest_better_md 应在 DANGEROUS_TOOLS 集合中");
    }

    // ==================== agent_memory_* 工具测试 ====================

    @Test
    void agentMemorySearchReturnsUninitializedWhenNoStore() {
        ToolRegistry registry = new ToolRegistry();
        String result = registry.executeTool("agent_memory_search", "{\"query\":\"测试\"}");
        assertTrue(result.contains("未初始化"), "未注入 store 时应返回未初始化提示");
    }

    @Test
    void agentMemorySearchReturnsResults(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            store.store(com.bettercli.memory.AgentMemoryEntry.builder()
                    .id("mem-1")
                    .content("项目使用 SQLite 作为本地存储数据库")
                    .keywords(List.of("SQLite", "数据库", "存储"))
                    .confidence(0.9)
                    .scope(com.bettercli.memory.AgentMemoryEntry.MemoryScope.PROJECT)
                    .project(tempDir.toString())
                    .build());
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_search",
                    "{\"query\":\"SQLite 数据库\",\"limit\":5}");

            assertTrue(result.contains("Agent 记忆检索"), "应包含检索标题");
            assertTrue(result.contains("SQLite"), "应包含匹配内容");
            assertTrue(result.contains("mem-1"), "应包含记忆 ID");
        }
    }

    @Test
    void agentMemorySearchReturnsEmptyMessageWhenNoMatch(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_search",
                    "{\"query\":\"不存在的关键词\"}");

            assertTrue(result.contains("未找到"), "无匹配时应返回未找到提示");
        }
    }

    @Test
    void agentMemorySearchRejectsEmptyQuery(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_search", "{\"query\":\"\"}");
            assertTrue(result.contains("query 不能为空"));
        }
    }

    @Test
    void sessionSearchReturnsUninitializedWhenNoStore() {
        ToolRegistry registry = new ToolRegistry();
        String result = registry.executeTool("session_search", "{\"query\":\"测试\"}");
        assertTrue(result.contains("未初始化"), "未注入 store 时应返回未初始化提示");
    }

    @Test
    void sessionSearchReturnsResults(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteSessionMessageStore store =
                     new com.bettercli.memory.SqliteSessionMessageStore(tempDir.toFile())) {
            store.index(com.bettercli.memory.SessionMessage.builder()
                    .id("m1").conversationId("c1").role("user")
                    .content("如何配置 SQLite FTS5 全文检索").project(tempDir.toString())
                    .createdAt(java.time.Instant.now()).build());
            store.index(com.bettercli.memory.SessionMessage.builder()
                    .id("m2").conversationId("c1").role("assistant")
                    .content("使用 FTS5 模块和 BM25 排序").project(tempDir.toString())
                    .createdAt(java.time.Instant.now()).build());
            registry.setSessionMessageStore(store);

            String result = registry.executeTool("session_search",
                    "{\"query\":\"SQLite FTS5\",\"limit\":3,\"days_back\":30}");

            assertTrue(result.contains("历史会话检索"), "应包含检索标题");
            assertTrue(result.contains("c1"), "应包含会话 ID");
            assertTrue(result.contains("SQLite"), "应包含命中内容");
        }
    }

    @Test
    void sessionSearchReturnsEmptyMessageWhenNoMatch(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteSessionMessageStore store =
                     new com.bettercli.memory.SqliteSessionMessageStore(tempDir.toFile())) {
            store.index(com.bettercli.memory.SessionMessage.builder()
                    .id("m1").conversationId("c1").role("user")
                    .content("完全无关的对话").project(tempDir.toString())
                    .createdAt(java.time.Instant.now()).build());
            registry.setSessionMessageStore(store);

            String result = registry.executeTool("session_search",
                    "{\"query\":\"SQLite FTS5\"}");
            assertTrue(result.contains("未找到"));
        }
    }

    @Test
    void sessionSearchRejectsEmptyQuery(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteSessionMessageStore store =
                     new com.bettercli.memory.SqliteSessionMessageStore(tempDir.toFile())) {
            registry.setSessionMessageStore(store);

            String result = registry.executeTool("session_search", "{\"query\":\"\"}");
            assertTrue(result.contains("query 不能为空"));
        }
    }

    @Test
    void sessionSearchDoesNotRequireHitl() {
        assertFalse(com.bettercli.hitl.ApprovalPolicy.requiresApproval("session_search"),
                "session_search 是只读检索工具，不应触发 HITL");
    }

    @Test
    void agentMemorySaveStoresEntry(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_save",
                    "{\"fact\":\"项目使用 SQLite 作为本地存储\","
                            + "\"keywords\":\"SQLite,数据库,存储\","
                            + "\"confidence\":0.9,"
                            + "\"type\":\"FACT\"}");

            assertTrue(result.contains("已保存到 Agent 记忆"), "应提示保存成功");
            assertTrue(result.contains("mem-"), "应返回记忆 ID");
            assertEquals(1, store.size());
        }
    }

    @Test
    void agentMemorySaveRejectsLowConfidence(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_save",
                    "{\"fact\":\"低置信度事实\","
                            + "\"keywords\":\"kw1,kw2,kw3\","
                            + "\"confidence\":0.5,"
                            + "\"type\":\"FACT\"}");

            assertTrue(result.contains("低于 0.7 门槛"), "低置信度应拒绝");
            assertEquals(0, store.size());
        }
    }

    @Test
    void agentMemorySaveRejectsSensitiveContent(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_save",
                    "{\"fact\":\"API key 是 sk-1234567890\","
                            + "\"keywords\":\"api,key,secret\","
                            + "\"confidence\":0.9,"
                            + "\"type\":\"FACT\"}");

            assertTrue(result.contains("API key"), "敏感内容应被拦截");
            assertEquals(0, store.size());
        }
    }

    @Test
    void agentMemorySaveRejectsInvalidKeywordCount(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String tooFew = registry.executeTool("agent_memory_save",
                    "{\"fact\":\"测试\",\"keywords\":\"kw1,kw2\","
                            + "\"confidence\":0.9,\"type\":\"FACT\"}");
            assertTrue(tooFew.contains("3-8 个"), "关键词少于 3 个应拒绝");

            String tooMany = registry.executeTool("agent_memory_save",
                    "{\"fact\":\"测试\",\"keywords\":\"k1,k2,k3,k4,k5,k6,k7,k8,k9\","
                            + "\"confidence\":0.9,\"type\":\"FACT\"}");
            assertTrue(tooMany.contains("3-8 个"), "关键词超过 8 个应拒绝");
        }
    }

    @Test
    void agentMemorySaveSkipsDuplicateContent(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            // 先保存一条
            registry.executeTool("agent_memory_save",
                    "{\"fact\":\"项目使用 SQLite 作为本地存储\","
                            + "\"keywords\":\"SQLite,数据库,存储\","
                            + "\"confidence\":0.9,\"type\":\"FACT\"}");
            assertEquals(1, store.size());

            // 再保存高度相似的内容
            String result = registry.executeTool("agent_memory_save",
                    "{\"fact\":\"项目使用 SQLite 作为本地存储数据库\","
                            + "\"keywords\":\"SQLite,数据库,存储\","
                            + "\"confidence\":0.9,\"type\":\"FACT\"}");

            assertTrue(result.contains("高度相似"), "重复内容应被跳过");
            assertEquals(1, store.size(), "重复保存不应增加条目");
        }
    }

    @Test
    void agentMemoryUpdateModifiesEntry(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            // 先保存
            store.store(com.bettercli.memory.AgentMemoryEntry.builder()
                    .id("mem-1")
                    .content("原始内容")
                    .keywords(List.of("kw1", "kw2", "kw3"))
                    .confidence(0.7)
                    .build());

            String result = registry.executeTool("agent_memory_update",
                    "{\"id\":\"mem-1\",\"content\":\"更新后的内容\",\"confidence\":0.95}");

            assertTrue(result.contains("已更新 Agent 记忆"), "应提示更新成功");
            assertTrue(result.contains("mem-1"));
            assertEquals(0.95, store.retrieve("mem-1").get().getConfidence());
            assertEquals("更新后的内容", store.retrieve("mem-1").get().getContent());
        }
    }

    @Test
    void agentMemoryUpdateReturnsFailureForMissingId(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_update",
                    "{\"id\":\"nonexistent\",\"content\":\"新内容\"}");

            assertTrue(result.contains("未找到"), "missing id 应返回未找到");
        }
    }

    @Test
    void agentMemoryDeleteRemovesEntry(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            store.store(com.bettercli.memory.AgentMemoryEntry.builder()
                    .id("mem-1").content("内容").build());
            assertEquals(1, store.size());

            String result = registry.executeTool("agent_memory_delete",
                    "{\"id\":\"mem-1\"}");

            assertTrue(result.contains("已删除 Agent 记忆"), "应提示删除成功");
            assertEquals(0, store.size());
        }
    }

    @Test
    void agentMemoryDeleteReturnsFailureForMissingId(@TempDir Path tempDir) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        try (com.bettercli.memory.SqliteAgentMemoryStore store =
                     new com.bettercli.memory.SqliteAgentMemoryStore(tempDir.toString(), tempDir.toFile(), 100, 0.85)) {
            registry.setAgentMemoryStore(store);

            String result = registry.executeTool("agent_memory_delete",
                    "{\"id\":\"nonexistent\"}");

            assertTrue(result.contains("未找到"), "missing id 应返回未找到");
        }
    }

    @Test
    void agentMemoryToolsNotInDangerousToolsSet() {
        // agent_memory_* 工具不走 HITL（Agent 自主决策），不应在 DANGEROUS_TOOLS 集合中
        assertFalse(com.bettercli.hitl.ApprovalPolicy.requiresApproval("agent_memory_search"),
                "agent_memory_search 不应触发 HITL");
        assertFalse(com.bettercli.hitl.ApprovalPolicy.requiresApproval("agent_memory_save"),
                "agent_memory_save 不应触发 HITL");
        assertFalse(com.bettercli.hitl.ApprovalPolicy.requiresApproval("agent_memory_update"),
                "agent_memory_update 不应触发 HITL");
        assertFalse(com.bettercli.hitl.ApprovalPolicy.requiresApproval("agent_memory_delete"),
                "agent_memory_delete 不应触发 HITL");
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
