package com.bettercli.agent;

import java.util.Set;

/**
 * Agent 角色定义 - Multi-Agent 系统中的角色分工
 *
 * 每个角色附带工具白名单（{@link #allowedTools()}），用于在 SubAgent 调用 LLM 时
 * 只暴露该角色允许使用的工具 schema，并在工具执行层拦截越权调用。
 *
 * 设计原则：最小权限
 * - PLANNER  只读 + 调研 + load_skill，不能写文件、不能执行命令、不能改记忆
 * - WORKER   全部内置工具（执行者要能动手）
 * - REVIEWER 只读 + load_skill，不能联网、不能写、不能执行（避免审查者被外部信息带偏或误改代码）
 *
 * 返回 null 表示"不限制"（兼容 WORKER 全量场景，避免硬编码全量列表导致新增工具时漏配）。
 */
public enum AgentRole {
    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 该角色允许使用的内置工具名集合。
     *
     * 返回 {@code null} 表示不限制（WORKER 全量）。
     * 返回非空 {@link Set} 时，SubAgent 只会把白名单内的工具 schema 下发给 LLM，
     * 且 ToolRegistry 在执行阶段会对不在白名单内的调用直接拒绝。
     *
     * MCP 动态工具（mcp__*）不在此白名单约束内，由 ToolRegistry 单独管控。
     */
    public Set<String> allowedTools() {
        return switch (this) {
            case PLANNER -> Set.of(
                    "read_file",
                    "glob_files",
                    "grep_code",
                    "list_dir",
                    "web_search",
                    "web_fetch",
                    "load_skill"
            );
            case REVIEWER -> Set.of(
                    "read_file",
                    "glob_files",
                    "grep_code",
                    "list_dir",
                    "load_skill"
            );
            case WORKER -> null;
        };
    }
}
