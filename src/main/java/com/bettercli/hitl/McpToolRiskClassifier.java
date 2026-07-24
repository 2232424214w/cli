package com.bettercli.hitl;

/**
 * MCP 工具读写风险启发式：按 namespaced 工具名末段语义区分只读 / 写入，
 * 只读 MCP 免 HITL，写入仍需审批（路线图「MCP 细粒度权限」）。
 *
 * <p>无 MCP annotations 时用命名启发式；命中写入关键词优先于只读关键词。
 */
public final class McpToolRiskClassifier {

    private McpToolRiskClassifier() {
    }

    public static boolean isReadOnly(String toolName) {
        if (toolName == null || !toolName.startsWith("mcp__")) {
            return false;
        }
        String leaf = leafName(toolName);
        if (leaf.isEmpty()) {
            return false;
        }
        String n = leaf.toLowerCase().replace('-', '_');
        if (matchesWrite(n)) {
            return false;
        }
        return matchesRead(n);
    }

    public static boolean requiresApproval(String toolName) {
        return toolName != null && toolName.startsWith("mcp__") && !isReadOnly(toolName);
    }

    static String leafName(String toolName) {
        String[] parts = toolName.split("__", 3);
        return parts.length >= 3 ? parts[2] : "";
    }

    private static boolean matchesWrite(String n) {
        return containsAny(n,
                "write", "create", "delete", "remove", "update", "put", "post", "patch",
                "execute", "run", "call", "invoke", "send", "click", "type_text", "type_",
                "fill", "press", "navigate", "goto", "upload", "download_to", "install",
                "uninstall", "kill", "stop", "start_session", "close_page", "evaluate",
                "set_", "add_", "append", "replace", "rename", "move", "copy_to", "mkdir",
                "rmdir", "rm_", "drop_", "truncate", "commit", "push", "apply");
    }

    private static boolean matchesRead(String n) {
        return containsAny(n,
                "read", "get", "list", "search", "find", "fetch", "query", "lookup",
                "describe", "show", "view", "stat", "info", "status", "inspect",
                "snapshot", "screenshot", "take_snapshot", "take_screenshot",
                "browse", "open_resource", "list_resources", "list_tools", "ping",
                "echo", "health", "version", "schema", "catalog", "count");
    }

    private static boolean containsAny(String n, String... needles) {
        for (String needle : needles) {
            if (n.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
