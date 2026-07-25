package com.bettercli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliCommandParserTest {

    @Test
    void parsesPlanSlashCommandWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/plan");

        assertEquals(CliCommandParser.CommandType.SWITCH_PLAN, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesPlanSlashCommandWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/plan 创建一个 demo 项目");

        assertEquals(CliCommandParser.CommandType.SWITCH_PLAN, command.type());
        assertEquals("创建一个 demo 项目", command.payload());
    }

    @Test
    void parsesInitProjectMemoryCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/init");

        assertEquals(CliCommandParser.CommandType.INIT_PROJECT_MEMORY, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesInitProjectMemoryForceCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/init --force");

        assertEquals(CliCommandParser.CommandType.INIT_PROJECT_MEMORY, command.type());
        assertEquals("--force", command.payload());
    }

    @Test
    void parsesLangCommand() {
        CliCommandParser.ParsedCommand status = CliCommandParser.parse("/lang");
        assertEquals(CliCommandParser.CommandType.LANG, status.type());
        assertNull(status.payload());

        CliCommandParser.ParsedCommand zh = CliCommandParser.parse("/lang zh");
        assertEquals(CliCommandParser.CommandType.LANG, zh.type());
        assertEquals("zh", zh.payload());

        CliCommandParser.ParsedCommand en = CliCommandParser.parse("/language en");
        assertEquals(CliCommandParser.CommandType.LANG, en.type());
        assertEquals("en", en.payload());
    }

    @Test
    void resolvesConcreteModelNameToProviderAndModel() {
        Main.ModelSelection step = Main.resolveModelSelection("step-custom-model");
        assertEquals("step", step.provider());
        assertEquals("step-custom-model", step.model());
        assertEquals(true, step.explicitModel());

        Main.ModelSelection glm = Main.resolveModelSelection("glm-4v-plus");
        assertEquals("glm", glm.provider());
        assertEquals("glm-4v-plus", glm.model());

        Main.ModelSelection provider = Main.resolveModelSelection("step");
        assertEquals("step", provider.provider());
        assertNull(provider.model());
        assertEquals(false, provider.explicitModel());

        Main.ModelSelection defaultGlm = Main.resolveModelSelection("glm");
        assertEquals("glm", defaultGlm.provider());
        assertEquals("glm-5.1", defaultGlm.model());
        assertEquals(true, defaultGlm.explicitModel());

        Main.ModelSelection explicitGlm = Main.resolveModelSelection("glm-5.1");
        assertEquals("glm", explicitGlm.provider());
        assertEquals("glm-5.1", explicitGlm.model());
        assertEquals(true, explicitGlm.explicitModel());

        Main.ModelSelection kimi = Main.resolveModelSelection("kimi-k2.6");
        assertEquals("kimi", kimi.provider());
        assertEquals("kimi-k2.6", kimi.model());
        assertEquals(true, kimi.explicitModel());

        Main.ModelSelection moonshot = Main.resolveModelSelection("moonshot");
        assertEquals("kimi", moonshot.provider());
        assertNull(moonshot.model());
        assertEquals(false, moonshot.explicitModel());

        Main.ModelSelection freeLlmApi = Main.resolveModelSelection("free-llm-api");
        assertEquals("freellmapi", freeLlmApi.provider());
        assertNull(freeLlmApi.model());
        assertEquals(false, freeLlmApi.explicitModel());

        Main.ModelSelection xfyun = Main.resolveModelSelection("maas");
        assertEquals("xfyun", xfyun.provider());
        assertNull(xfyun.model());
        assertEquals(false, xfyun.explicitModel());

        Main.ModelSelection agnes = Main.resolveModelSelection("agnes-2.0-flash");
        assertEquals("agnes", agnes.provider());
        assertEquals("agnes-2.0-flash", agnes.model());
        assertEquals(true, agnes.explicitModel());
    }

    @Test
    void parsesConfigProviderPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse(
                "/config provider freellmapi --base-url http://localhost:5173/v1 --model auto");

        assertEquals(CliCommandParser.CommandType.CONFIG, command.type());
        assertEquals("provider freellmapi --base-url http://localhost:5173/v1 --model auto", command.payload());
    }

    @Test
    void parsesProviderConfigUpdate() {
        Main.ProviderConfigUpdate update = Main.parseProviderConfigUpdate(
                "provider free-llm-api --base-url http://localhost:5173/v1 --api-key sk-test --model auto --default");

        assertNull(update.error());
        assertEquals("freellmapi", update.provider());
        assertEquals("http://localhost:5173/v1", update.baseUrl());
        assertEquals("sk-test", update.apiKey());
        assertEquals("auto", update.model());
        assertEquals(true, update.setDefault());
    }

    @Test
    void parsesXfyunProviderConfigUpdate() {
        Main.ProviderConfigUpdate update = Main.parseProviderConfigUpdate(
                "provider xfyun --base-url https://maas-api.cn-huabei-1.xf-yun.com/v2 --api-key sk-test --model Qwen3.6-35B-A3B --lora-id 0 --default");

        assertNull(update.error());
        assertEquals("xfyun", update.provider());
        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2", update.baseUrl());
        assertEquals("sk-test", update.apiKey());
        assertEquals("Qwen3.6-35B-A3B", update.model());
        assertEquals("0", update.loraId());
        assertEquals(true, update.setDefault());
    }

    @Test
    void parsesAgnesProviderConfigUpdate() {
        Main.ProviderConfigUpdate update = Main.parseProviderConfigUpdate(
                "provider agnes-ai --base-url https://apihub.agnes-ai.com/v1 --api-key sk-test --model agnes-2.0-flash --default");

        assertNull(update.error());
        assertEquals("agnes", update.provider());
        assertEquals("https://apihub.agnes-ai.com/v1", update.baseUrl());
        assertEquals("sk-test", update.apiKey());
        assertEquals("agnes-2.0-flash", update.model());
        assertEquals(true, update.setDefault());
    }

    @Test
    void redactsApiKeyInSubmittedInput() {
        String redacted = Main.redactSensitiveInput(
                "/config provider freellmapi --api-key sk-secret --model auto");

        assertEquals("/config provider freellmapi --api-key *** --model auto", redacted);
    }

    @Test
    void parsesClearSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/clear");

        assertEquals(CliCommandParser.CommandType.CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesCompactSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/compact");

        assertEquals(CliCommandParser.CommandType.COMPACT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesExportSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/export");

        assertEquals(CliCommandParser.CommandType.EXPORT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesWechatSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/wechat");

        assertEquals(CliCommandParser.CommandType.WECHAT, command.type());
        assertEquals("start", command.payload());
    }

    @Test
    void parsesWechatSlashCommandWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/wechat status");

        assertEquals(CliCommandParser.CommandType.WECHAT, command.type());
        assertEquals("status", command.payload());
    }

    @Test
    void exportSlashCommandDoesNotAcceptIgnoredArguments() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/export ./session.md");

        assertEquals(CliCommandParser.CommandType.UNKNOWN_COMMAND, command.type());
        assertEquals("/export ./session.md", command.payload());
    }

    @Test
    void parsesHistoryClearSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/history clear");

        assertEquals(CliCommandParser.CommandType.HISTORY_CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesExitSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/exit");

        assertEquals(CliCommandParser.CommandType.EXIT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemorySlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory");

        assertEquals(CliCommandParser.CommandType.MEMORY_STATUS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemoryClearSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory clear");

        assertEquals(CliCommandParser.CommandType.MEMORY_CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemoryListSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory list");

        assertEquals(CliCommandParser.CommandType.MEMORY_LIST, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesMemorySearchSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory search Chrome 登录态");

        assertEquals(CliCommandParser.CommandType.MEMORY_SEARCH, command.type());
        assertEquals("Chrome 登录态", command.payload());
    }

    @Test
    void parsesAgentMemoryStatsSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/agent-memory");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_STATS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAgentMemoryStatsSlashCommandExplicit() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/agent-memory stats");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_STATS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAgentMemoryListSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/agent-memory list");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_LIST, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAgentMemorySearchSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/agent-memory search 数据库选型");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_SEARCH, command.type());
        assertEquals("数据库选型", command.payload());
    }

    @Test
    void parsesAgentMemoryExportSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/agent-memory export");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_EXPORT, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAgentMemoryClearSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/agent-memory clear");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_CLEAR, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAgentMemoryShortAliasSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/am list");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_LIST, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAgentMemorySearchShortAliasSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/am search SQLite");

        assertEquals(CliCommandParser.CommandType.AGENT_MEMORY_SEARCH, command.type());
        assertEquals("SQLite", command.payload());
    }

    @Test
    void parsesMemoryDeleteSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/memory delete fact-abcd1234");

        assertEquals(CliCommandParser.CommandType.MEMORY_DELETE, command.type());
        assertEquals("fact-abcd1234", command.payload());
    }

    @Test
    void parsesSaveSlashCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/save 记住这个事实");

        assertEquals(CliCommandParser.CommandType.MEMORY_SAVE, command.type());
        assertEquals("记住这个事实", command.payload());
    }

    @Test
    void parsesSaveWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/save");

        assertEquals(CliCommandParser.CommandType.MEMORY_SAVE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesSearchWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/search");

        assertEquals(CliCommandParser.CommandType.SEARCH_CODE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesGraphWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/graph");

        assertEquals(CliCommandParser.CommandType.GRAPH_QUERY, command.type());
        assertNull(command.payload());
    }

    @Test
    void keepsNormalInputAsNone() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("帮我读取 pom.xml");

        assertEquals(CliCommandParser.CommandType.NONE, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesUnknownSlashCommandAsUnknownCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/unknown");

        assertEquals(CliCommandParser.CommandType.UNKNOWN_COMMAND, command.type());
        assertEquals("/unknown", command.payload());
    }

    @Test
    void parsesTeamSlashCommandWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/team");

        assertEquals(CliCommandParser.CommandType.SWITCH_TEAM, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesTeamSlashCommandWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/team 创建并验证一个 Java 项目");

        assertEquals(CliCommandParser.CommandType.SWITCH_TEAM, command.type());
        assertEquals("创建并验证一个 Java 项目", command.payload());
    }

    @Test
    void parsesHitlOnCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/hitl on");

        assertEquals(CliCommandParser.CommandType.SWITCH_HITL, command.type());
        assertEquals("on", command.payload());
    }

    @Test
    void parsesHitlOffCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/hitl off");

        assertEquals(CliCommandParser.CommandType.SWITCH_HITL, command.type());
        assertEquals("off", command.payload());
    }

    @Test
    void parsesHitlStatusCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/hitl");

        assertEquals(CliCommandParser.CommandType.SWITCH_HITL, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesPolicyStatusCommand() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/policy");

        assertEquals(CliCommandParser.CommandType.POLICY_STATUS, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAuditTailWithoutPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/audit");

        assertEquals(CliCommandParser.CommandType.AUDIT_TAIL, command.type());
        assertNull(command.payload());
    }

    @Test
    void parsesAuditTailWithPayload() {
        CliCommandParser.ParsedCommand command = CliCommandParser.parse("/audit 20");

        assertEquals(CliCommandParser.CommandType.AUDIT_TAIL, command.type());
        assertEquals("20", command.payload());
    }

    @Test
    void parsesSnapshotCommands() {
        assertEquals(CliCommandParser.CommandType.SNAPSHOT, CliCommandParser.parse("/snapshot").type());
        assertEquals("list", CliCommandParser.parse("/snapshot").payload());
        assertEquals(CliCommandParser.CommandType.SNAPSHOT, CliCommandParser.parse("/snapshot status").type());
        assertEquals("status", CliCommandParser.parse("/snapshot status").payload());
        assertEquals(CliCommandParser.CommandType.RESTORE_SNAPSHOT, CliCommandParser.parse("/restore 2").type());
        assertEquals("2", CliCommandParser.parse("/restore 2").payload());
    }

    @Test
    void parsesMcpCommands() {
        assertEquals(CliCommandParser.CommandType.MCP_LIST, CliCommandParser.parse("/mcp").type());
        assertEquals(CliCommandParser.CommandType.MCP_RESTART, CliCommandParser.parse("/mcp restart filesystem").type());
        assertEquals("filesystem", CliCommandParser.parse("/mcp restart filesystem").payload());
        assertEquals(CliCommandParser.CommandType.MCP_LOGS, CliCommandParser.parse("/mcp logs filesystem").type());
        assertEquals(CliCommandParser.CommandType.MCP_DISABLE, CliCommandParser.parse("/mcp disable filesystem").type());
        assertEquals(CliCommandParser.CommandType.MCP_ENABLE, CliCommandParser.parse("/mcp enable filesystem").type());
        assertEquals(CliCommandParser.CommandType.MCP_RESOURCES, CliCommandParser.parse("/mcp resources filesystem").type());
        assertEquals("filesystem", CliCommandParser.parse("/mcp resources filesystem").payload());
        assertEquals(CliCommandParser.CommandType.MCP_PROMPTS, CliCommandParser.parse("/mcp prompts filesystem").type());
        assertEquals("filesystem", CliCommandParser.parse("/mcp prompts filesystem").payload());
    }

    @Test
    void parsesBrowserCommands() {
        assertEquals(CliCommandParser.CommandType.BROWSER, CliCommandParser.parse("/browser").type());
        assertEquals("status", CliCommandParser.parse("/browser").payload());
        assertEquals(CliCommandParser.CommandType.BROWSER, CliCommandParser.parse("/browser status").type());
        assertEquals("status", CliCommandParser.parse("/browser status").payload());
        assertEquals("connect", CliCommandParser.parse("/browser connect").payload());
        assertEquals("connect 9333", CliCommandParser.parse("/browser connect 9333").payload());
        assertEquals("disconnect", CliCommandParser.parse("/browser disconnect").payload());
        assertEquals("tabs", CliCommandParser.parse("/browser tabs").payload());
    }

    @Test
    void parsesTaskCommands() {
        assertEquals(CliCommandParser.CommandType.TASK, CliCommandParser.parse("/task").type());
        assertEquals("list", CliCommandParser.parse("/task").payload());
        assertEquals("add 重构模块", CliCommandParser.parse("/task add 重构模块").payload());
        assertEquals("cancel task_123", CliCommandParser.parse("/task cancel task_123").payload());
        assertEquals("log task_123", CliCommandParser.parse("/task log task_123").payload());
    }

    @Test
    void parsesCancelCommand() {
        assertEquals(CliCommandParser.CommandType.CANCEL, CliCommandParser.parse("/cancel").type());
        assertEquals(CliCommandParser.CommandType.CANCEL, CliCommandParser.parse("cancel").type());
    }

    @Test
    void parsesSkillListCommand() {
        assertEquals(CliCommandParser.CommandType.SKILL_LIST, CliCommandParser.parse("/skill").type());
        assertEquals(CliCommandParser.CommandType.SKILL_LIST, CliCommandParser.parse("/skill list").type());
    }

    @Test
    void parsesSkillReloadCommand() {
        assertEquals(CliCommandParser.CommandType.SKILL_RELOAD, CliCommandParser.parse("/skill reload").type());
    }

    @Test
    void parsesSkillShowCommand() {
        CliCommandParser.ParsedCommand cmd = CliCommandParser.parse("/skill show web-access");
        assertEquals(CliCommandParser.CommandType.SKILL_SHOW, cmd.type());
        assertEquals("web-access", cmd.payload());
    }

    @Test
    void parsesSkillOnOffCommands() {
        CliCommandParser.ParsedCommand on = CliCommandParser.parse("/skill on web-access");
        assertEquals(CliCommandParser.CommandType.SKILL_ON, on.type());
        assertEquals("web-access", on.payload());

        CliCommandParser.ParsedCommand off = CliCommandParser.parse("/skill off verbose-debug");
        assertEquals(CliCommandParser.CommandType.SKILL_OFF, off.type());
        assertEquals("verbose-debug", off.payload());
    }

    @Test
    void parsesSubagentListAndReloadOnly() {
        assertEquals(CliCommandParser.CommandType.SUBAGENT_LIST, CliCommandParser.parse("/subagent").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_LIST, CliCommandParser.parse("/subagent list").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_LIST, CliCommandParser.parse("/sa").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_LIST, CliCommandParser.parse("/sa-l").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_RELOAD, CliCommandParser.parse("/subagent reload").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_RELOAD, CliCommandParser.parse("/sa reload").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_STATUS, CliCommandParser.parse("/subagent status").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_STATUS, CliCommandParser.parse("/sa-st").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_TEMPLATES, CliCommandParser.parse("/subagent templates").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_AUDIT, CliCommandParser.parse("/subagent audit").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_AUDIT, CliCommandParser.parse("/subagent audit 50").type());
        assertEquals("50", CliCommandParser.parse("/subagent audit 50").payload());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_SHOW,
                CliCommandParser.parse("/subagent show code-reviewer").type());
        assertEquals("code-reviewer", CliCommandParser.parse("/subagent show code-reviewer").payload());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_SESSIONS,
                CliCommandParser.parse("/subagent sessions").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_RESUME,
                CliCommandParser.parse("/subagent resume").type());
        assertEquals("sub_x", CliCommandParser.parse("/subagent resume sub_x").payload());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_STATS,
                CliCommandParser.parse("/subagent stats").type());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_DELETE,
                CliCommandParser.parse("/subagent delete foo --force").type());
        assertEquals("foo --force", CliCommandParser.parse("/subagent delete foo --force").payload());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_CREATE, CliCommandParser.parse("/subagent create").type());
        assertEquals("", CliCommandParser.parse("/subagent create").payload());
        CliCommandParser.ParsedCommand create = CliCommandParser.parse(
                "/subagent create my-bot --template researcher --user");
        assertEquals(CliCommandParser.CommandType.SUBAGENT_CREATE, create.type());
        assertEquals("my-bot --template researcher --user", create.payload());
        assertEquals(CliCommandParser.CommandType.SUBAGENT_CREATE,
                CliCommandParser.parse("/sa create x -t blank").type());
    }

    @Test
    void subagentColonPrefixIsNotUnknownCommand() {
        // 方式三：/subagent:name 放行给入站路由，不当未知斜杠命令
        assertEquals(CliCommandParser.CommandType.NONE,
                CliCommandParser.parse("/subagent:code-reviewer 审查这段").type());
        assertEquals(CliCommandParser.CommandType.NONE,
                CliCommandParser.parse("/sa:sql-analyzer 看慢查询").type());
    }

    @Test
    void subagentNameTaskIsNotAnExecutionCommand() {
        // 禁止空格硬指定：/subagent foo bar 视为未知命令
        CliCommandParser.ParsedCommand cmd = CliCommandParser.parse("/subagent code-reviewer 审查这段代码");
        assertEquals(CliCommandParser.CommandType.UNKNOWN_COMMAND, cmd.type());
    }
}
