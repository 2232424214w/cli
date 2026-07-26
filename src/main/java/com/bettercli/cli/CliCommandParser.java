package com.bettercli.cli;

final class CliCommandParser {

    enum CommandType {
        NONE,
        UNKNOWN_COMMAND,
        INIT_PROJECT_MEMORY,
        CANCEL,
        EXIT,
        CLEAR,
        COMPACT,
        HISTORY_CLEAR,
        SWITCH_MODEL,
        SWITCH_PLAN,
        SWITCH_TEAM,
        SWITCH_HITL,
        MEMORY_STATUS,
        MEMORY_CLEAR,
        MEMORY_LIST,
        MEMORY_DELETE,
        MEMORY_SEARCH,
        MEMORY_SAVE,
        AGENT_MEMORY_LIST,
        AGENT_MEMORY_SEARCH,
        AGENT_MEMORY_STATS,
        AGENT_MEMORY_EXPORT,
        AGENT_MEMORY_CLEAR,
        INDEX_CODE,
        SEARCH_CODE,
        GRAPH_QUERY,
        CONTEXT_STATUS,
        POLICY_STATUS,
        AUDIT_TAIL,
        SNAPSHOT,
        RESTORE_SNAPSHOT,
        MCP_LIST,
        MCP_RESTART,
        MCP_LOGS,
        MCP_DISABLE,
        MCP_ENABLE,
        MCP_RESOURCES,
        MCP_PROMPTS,
        BROWSER,
        WECHAT,
        TASK,
        SKILL_LIST,
        SKILL_SHOW,
        SKILL_ON,
        SKILL_OFF,
        SKILL_RELOAD,
        SUBAGENT_LIST,
        SUBAGENT_RELOAD,
        SUBAGENT_STATUS,
        SUBAGENT_CREATE,
        SUBAGENT_TEMPLATES,
        SUBAGENT_AUDIT,
        SUBAGENT_SHOW,
        SUBAGENT_SESSIONS,
        SUBAGENT_RESUME,
        SUBAGENT_DELETE,
        SUBAGENT_STATS,
        CONFIG,
        LANG,
        EXPORT
    }

    record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.none();
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return ParsedCommand.none();
        }

        if (trimmed.equalsIgnoreCase("/exit")
                || trimmed.equalsIgnoreCase("/quit")
                || trimmed.equalsIgnoreCase("exit")
                || trimmed.equalsIgnoreCase("quit")) {
            return new ParsedCommand(CommandType.EXIT, null);
        }

        if (trimmed.equalsIgnoreCase("/cancel") || trimmed.equalsIgnoreCase("cancel")) {
            return new ParsedCommand(CommandType.CANCEL, null);
        }

        if (trimmed.equalsIgnoreCase("/clear") || trimmed.equalsIgnoreCase("clear")) {
            return new ParsedCommand(CommandType.CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/compact")) {
            return new ParsedCommand(CommandType.COMPACT, null);
        }

        if (trimmed.equalsIgnoreCase("/history clear")) {
            return new ParsedCommand(CommandType.HISTORY_CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/init")) {
            return new ParsedCommand(CommandType.INIT_PROJECT_MEMORY, null);
        }

        if (trimmed.regionMatches(true, 0, "/init ", 0, 6)) {
            return new ParsedCommand(CommandType.INIT_PROJECT_MEMORY, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/model")) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, null);
        }

        if (trimmed.regionMatches(true, 0, "/model ", 0, 7)) {
            return new ParsedCommand(CommandType.SWITCH_MODEL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/plan")) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, null);
        }

        if (trimmed.regionMatches(true, 0, "/plan ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_PLAN, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/team")) {
            return new ParsedCommand(CommandType.SWITCH_TEAM, null);
        }

        if (trimmed.regionMatches(true, 0, "/team ", 0, 6)) {
            return new ParsedCommand(CommandType.SWITCH_TEAM, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/hitl on")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "on");
        }

        if (trimmed.equalsIgnoreCase("/hitl off")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, "off");
        }

        if (trimmed.equalsIgnoreCase("/hitl")) {
            return new ParsedCommand(CommandType.SWITCH_HITL, null);
        }

        if (trimmed.equalsIgnoreCase("/memory") || trimmed.equalsIgnoreCase("/mem")) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/memory clear") || trimmed.equalsIgnoreCase("/mem clear")) {
            return new ParsedCommand(CommandType.MEMORY_CLEAR, null);
        }

        if (trimmed.equalsIgnoreCase("/memory list") || trimmed.equalsIgnoreCase("/mem list")) {
            return new ParsedCommand(CommandType.MEMORY_LIST, null);
        }

        if (trimmed.regionMatches(true, 0, "/memory delete ", 0, 15)) {
            return new ParsedCommand(CommandType.MEMORY_DELETE, trimmed.substring(15).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mem delete ", 0, 12)) {
            return new ParsedCommand(CommandType.MEMORY_DELETE, trimmed.substring(12).trim());
        }

        if (trimmed.regionMatches(true, 0, "/memory search ", 0, 15)) {
            return new ParsedCommand(CommandType.MEMORY_SEARCH, trimmed.substring(15).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mem search ", 0, 12)) {
            return new ParsedCommand(CommandType.MEMORY_SEARCH, trimmed.substring(12).trim());
        }

        // /agent-memory 命令组（用户只读视图，对标美团 1024 Agent agent_memory 表）
        if (trimmed.equalsIgnoreCase("/agent-memory") || trimmed.equalsIgnoreCase("/am")) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_STATS, null);
        }
        if (trimmed.equalsIgnoreCase("/agent-memory list") || trimmed.equalsIgnoreCase("/am list")) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_LIST, null);
        }
        if (trimmed.equalsIgnoreCase("/agent-memory stats") || trimmed.equalsIgnoreCase("/am stats")) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_STATS, null);
        }
        if (trimmed.equalsIgnoreCase("/agent-memory export") || trimmed.equalsIgnoreCase("/am export")) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_EXPORT, null);
        }
        if (trimmed.equalsIgnoreCase("/agent-memory clear") || trimmed.equalsIgnoreCase("/am clear")) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_CLEAR, null);
        }
        if (trimmed.regionMatches(true, 0, "/agent-memory search ", 0, 21)) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_SEARCH, trimmed.substring(21).trim());
        }
        if (trimmed.regionMatches(true, 0, "/am search ", 0, 11)) {
            return new ParsedCommand(CommandType.AGENT_MEMORY_SEARCH, trimmed.substring(11).trim());
        }

        if (trimmed.equalsIgnoreCase("/save")) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, null);
        }

        if (trimmed.regionMatches(true, 0, "/save ", 0, 6)) {
            return new ParsedCommand(CommandType.MEMORY_SAVE, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/index")) {
            return new ParsedCommand(CommandType.INDEX_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/index ", 0, 7)) {
            return new ParsedCommand(CommandType.INDEX_CODE, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/search")) {
            return new ParsedCommand(CommandType.SEARCH_CODE, null);
        }

        if (trimmed.regionMatches(true, 0, "/search ", 0, 8)) {
            return new ParsedCommand(CommandType.SEARCH_CODE, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/graph")) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, null);
        }

        if (trimmed.regionMatches(true, 0, "/graph ", 0, 7)) {
            return new ParsedCommand(CommandType.GRAPH_QUERY, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/context") || trimmed.equalsIgnoreCase("/ctx")) {
            return new ParsedCommand(CommandType.CONTEXT_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/policy")) {
            return new ParsedCommand(CommandType.POLICY_STATUS, null);
        }

        if (trimmed.equalsIgnoreCase("/config")) {
            return new ParsedCommand(CommandType.CONFIG, null);
        }

        if (trimmed.regionMatches(true, 0, "/config ", 0, 8)) {
            return new ParsedCommand(CommandType.CONFIG, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/lang") || trimmed.equalsIgnoreCase("/language")) {
            return new ParsedCommand(CommandType.LANG, null);
        }

        if (trimmed.regionMatches(true, 0, "/lang ", 0, 6)) {
            return new ParsedCommand(CommandType.LANG, trimmed.substring(6).trim());
        }

        if (trimmed.regionMatches(true, 0, "/language ", 0, 10)) {
            return new ParsedCommand(CommandType.LANG, trimmed.substring(10).trim());
        }

        if (trimmed.equalsIgnoreCase("/audit")) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, null);
        }

        if (trimmed.regionMatches(true, 0, "/audit ", 0, 7)) {
            return new ParsedCommand(CommandType.AUDIT_TAIL, trimmed.substring(7).trim());
        }

        if (trimmed.equalsIgnoreCase("/snapshot")) {
            return new ParsedCommand(CommandType.SNAPSHOT, "list");
        }

        if (trimmed.regionMatches(true, 0, "/snapshot ", 0, 10)) {
            return new ParsedCommand(CommandType.SNAPSHOT, trimmed.substring(10).trim());
        }

        if (trimmed.equalsIgnoreCase("/restore")) {
            return new ParsedCommand(CommandType.RESTORE_SNAPSHOT, null);
        }

        if (trimmed.regionMatches(true, 0, "/restore ", 0, 9)) {
            return new ParsedCommand(CommandType.RESTORE_SNAPSHOT, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/browser")) {
            return new ParsedCommand(CommandType.BROWSER, "status");
        }

        if (trimmed.regionMatches(true, 0, "/browser ", 0, 9)) {
            return new ParsedCommand(CommandType.BROWSER, trimmed.substring(9).trim());
        }

        if (trimmed.equalsIgnoreCase("/wechat")) {
            return new ParsedCommand(CommandType.WECHAT, "start");
        }

        if (trimmed.regionMatches(true, 0, "/wechat ", 0, 8)) {
            return new ParsedCommand(CommandType.WECHAT, trimmed.substring(8).trim());
        }

        if (trimmed.equalsIgnoreCase("/task")) {
            return new ParsedCommand(CommandType.TASK, "list");
        }

        if (trimmed.regionMatches(true, 0, "/task ", 0, 6)) {
            return new ParsedCommand(CommandType.TASK, trimmed.substring(6).trim());
        }

        if (trimmed.equalsIgnoreCase("/skill") || trimmed.equalsIgnoreCase("/skill list")) {
            return new ParsedCommand(CommandType.SKILL_LIST, null);
        }

        if (trimmed.equalsIgnoreCase("/skill reload")) {
            return new ParsedCommand(CommandType.SKILL_RELOAD, null);
        }

        if (trimmed.regionMatches(true, 0, "/skill show ", 0, 12)) {
            return new ParsedCommand(CommandType.SKILL_SHOW, trimmed.substring(12).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill on ", 0, 10)) {
            return new ParsedCommand(CommandType.SKILL_ON, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/skill off ", 0, 11)) {
            return new ParsedCommand(CommandType.SKILL_OFF, trimmed.substring(11).trim());
        }

        // Custom Subagent：仅管理（list/reload/status/create/templates/audit），禁止 /subagent <name> <task> 空格硬指定
        // /subagent:name … /sa:name … 是消息前缀（方式三），放行给入站路由，不当未知命令
        if (com.bettercli.subagent.CustomSubAgentRouter.isDirectDesignatePrefix(trimmed)) {
            return ParsedCommand.none();
        }
        if (trimmed.equalsIgnoreCase("/subagent") || trimmed.equalsIgnoreCase("/subagent list")
                || trimmed.equalsIgnoreCase("/sa") || trimmed.equalsIgnoreCase("/sa list")
                || trimmed.equalsIgnoreCase("/sa-l")) {
            return new ParsedCommand(CommandType.SUBAGENT_LIST, null);
        }
        if (trimmed.equalsIgnoreCase("/subagent reload") || trimmed.equalsIgnoreCase("/sa reload")) {
            return new ParsedCommand(CommandType.SUBAGENT_RELOAD, null);
        }
        if (trimmed.equalsIgnoreCase("/subagent status") || trimmed.equalsIgnoreCase("/sa status")
                || trimmed.equalsIgnoreCase("/sa-st")) {
            return new ParsedCommand(CommandType.SUBAGENT_STATUS, null);
        }
        if (trimmed.equalsIgnoreCase("/subagent templates") || trimmed.equalsIgnoreCase("/sa templates")) {
            return new ParsedCommand(CommandType.SUBAGENT_TEMPLATES, null);
        }
        if (trimmed.equalsIgnoreCase("/subagent audit") || trimmed.equalsIgnoreCase("/sa audit")) {
            return new ParsedCommand(CommandType.SUBAGENT_AUDIT, null);
        }
        if (trimmed.regionMatches(true, 0, "/subagent audit ", 0, 16)) {
            return new ParsedCommand(CommandType.SUBAGENT_AUDIT, trimmed.substring(16).trim());
        }
        if (trimmed.regionMatches(true, 0, "/sa audit ", 0, 10)) {
            return new ParsedCommand(CommandType.SUBAGENT_AUDIT, trimmed.substring(10).trim());
        }
        if (trimmed.regionMatches(true, 0, "/subagent show ", 0, 15)) {
            return new ParsedCommand(CommandType.SUBAGENT_SHOW, trimmed.substring(15).trim());
        }
        if (trimmed.regionMatches(true, 0, "/sa show ", 0, 9)) {
            return new ParsedCommand(CommandType.SUBAGENT_SHOW, trimmed.substring(9).trim());
        }
        if (trimmed.equalsIgnoreCase("/subagent show") || trimmed.equalsIgnoreCase("/sa show")) {
            return new ParsedCommand(CommandType.SUBAGENT_SHOW, "");
        }
        if (trimmed.equalsIgnoreCase("/subagent sessions") || trimmed.equalsIgnoreCase("/sa sessions")) {
            return new ParsedCommand(CommandType.SUBAGENT_SESSIONS, null);
        }
        if (trimmed.regionMatches(true, 0, "/subagent sessions ", 0, 18)) {
            return new ParsedCommand(CommandType.SUBAGENT_SESSIONS, trimmed.substring(18).trim());
        }
        if (trimmed.regionMatches(true, 0, "/sa sessions ", 0, 13)) {
            return new ParsedCommand(CommandType.SUBAGENT_SESSIONS, trimmed.substring(13).trim());
        }
        if (trimmed.equalsIgnoreCase("/subagent resume") || trimmed.equalsIgnoreCase("/sa resume")) {
            return new ParsedCommand(CommandType.SUBAGENT_RESUME, "");
        }
        if (trimmed.regionMatches(true, 0, "/subagent resume ", 0, 17)) {
            return new ParsedCommand(CommandType.SUBAGENT_RESUME, trimmed.substring(17).trim());
        }
        if (trimmed.regionMatches(true, 0, "/sa resume ", 0, 11)) {
            return new ParsedCommand(CommandType.SUBAGENT_RESUME, trimmed.substring(11).trim());
        }
        if (trimmed.equalsIgnoreCase("/subagent stats") || trimmed.equalsIgnoreCase("/sa stats")) {
            return new ParsedCommand(CommandType.SUBAGENT_STATS, null);
        }
        if (trimmed.regionMatches(true, 0, "/subagent delete ", 0, 17)) {
            return new ParsedCommand(CommandType.SUBAGENT_DELETE, trimmed.substring(17).trim());
        }
        if (trimmed.regionMatches(true, 0, "/sa delete ", 0, 11)) {
            return new ParsedCommand(CommandType.SUBAGENT_DELETE, trimmed.substring(11).trim());
        }
        if (trimmed.equalsIgnoreCase("/subagent delete") || trimmed.equalsIgnoreCase("/sa delete")) {
            return new ParsedCommand(CommandType.SUBAGENT_DELETE, "");
        }
        if (trimmed.regionMatches(true, 0, "/subagent create", 0, 16)) {
            String rest = trimmed.length() > 16 ? trimmed.substring(16).trim() : "";
            return new ParsedCommand(CommandType.SUBAGENT_CREATE, rest);
        }
        if (trimmed.regionMatches(true, 0, "/sa create", 0, 10)) {
            String rest = trimmed.length() > 10 ? trimmed.substring(10).trim() : "";
            return new ParsedCommand(CommandType.SUBAGENT_CREATE, rest);
        }

        if (trimmed.equalsIgnoreCase("/export")) {
            return new ParsedCommand(CommandType.EXPORT, null);
        }

        if (trimmed.equalsIgnoreCase("/mcp")) {
            return new ParsedCommand(CommandType.MCP_LIST, null);
        }

        if (trimmed.regionMatches(true, 0, "/mcp resources ", 0, 15)) {
            return new ParsedCommand(CommandType.MCP_RESOURCES, trimmed.substring(15).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp prompts ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_PROMPTS, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp restart ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_RESTART, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp logs ", 0, 10)) {
            return new ParsedCommand(CommandType.MCP_LOGS, trimmed.substring(10).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp disable ", 0, 13)) {
            return new ParsedCommand(CommandType.MCP_DISABLE, trimmed.substring(13).trim());
        }

        if (trimmed.regionMatches(true, 0, "/mcp enable ", 0, 12)) {
            return new ParsedCommand(CommandType.MCP_ENABLE, trimmed.substring(12).trim());
        }

        if (trimmed.startsWith("/")) {
            return new ParsedCommand(CommandType.UNKNOWN_COMMAND, trimmed);
        }

        return ParsedCommand.none();
    }
}
