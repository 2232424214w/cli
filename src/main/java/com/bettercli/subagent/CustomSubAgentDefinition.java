package com.bettercli.subagent;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户/项目自定义子 Agent 定义（由 AGENT.md 解析）。
 *
 * <p>与 Multi-Agent 固定三角色（Planner/Worker/Reviewer）独立：本记录描述可被主 ReAct
 * 经语义识别后委托的专属 Agent。
 */
public record CustomSubAgentDefinition(
        String name,
        String description,
        String body,
        String model,
        Integer maxTurns,
        Integer timeoutSeconds,
        List<String> allowedTools,
        List<String> disallowedTools,
        List<String> skills,
        String memoryMd,
        String soulMd,
        String identityMd,
        Source source,
        Path agentMdPath
) {

    /** 子 Agent 内禁止再委托 / 拉起团队，防止递归。 */
    public static final Set<String> RECURSIVE_TOOLS = Set.of(
            "run_subagent",
            "run_team",
            "create_plan"
    );

    /** Custom 模式默认附带（除非黑名单）：Skill 加载与 MEMORY 写回。 */
    public static final Set<String> CUSTOM_SUPPORT_TOOLS = Set.of(
            "load_skill",
            "write_subagent_memory"
    );

    public enum Source {
        BUILTIN, USER, PROJECT
    }

    public CustomSubAgentDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Custom SubAgent name 不能为空");
        }
        if (description == null) {
            description = "";
        }
        if (body == null) {
            body = "";
        }
        if (allowedTools == null) {
            allowedTools = List.of();
        } else {
            allowedTools = List.copyOf(allowedTools);
        }
        if (disallowedTools == null) {
            disallowedTools = List.of();
        } else {
            disallowedTools = List.copyOf(disallowedTools);
        }
        if (skills == null) {
            skills = List.of();
        } else {
            skills = List.copyOf(skills);
        }
        if (memoryMd == null) {
            memoryMd = "";
        }
        if (soulMd == null) {
            soulMd = "";
        }
        if (identityMd == null) {
            identityMd = "";
        }
    }

    public String displaySource() {
        return switch (source) {
            case BUILTIN -> "builtin";
            case USER -> "user";
            case PROJECT -> "project";
        };
    }

    /**
     * 组装专属 system prompt 核心：AGENT.md body + 可选 SOUL.md / IDENTITY.md。
     */
    public String composeSystemPromptCore() {
        StringBuilder sb = new StringBuilder();
        if (body != null && !body.isBlank()) {
            sb.append(body.trim());
        }
        if (soulMd != null && !soulMd.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Soul\n\n").append(soulMd.trim());
        }
        if (identityMd != null && !identityMd.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("## Identity\n\n").append(identityMd.trim());
        }
        return sb.toString();
    }

    /** MEMORY.md 路径：与 AGENT.md 同目录。 */
    public Path memoryFilePath() {
        if (agentMdPath == null) {
            return null;
        }
        Path parent = agentMdPath.getParent();
        return parent == null ? null : parent.resolve("MEMORY.md");
    }

    /**
     * 计算实际可下发的工具名集合：白名单（空=全量已注册）减去黑名单，再强制去掉递归工具；
     * 并附带 load_skill / write_subagent_memory（除非黑名单）。
     */
    public Set<String> resolveEffectiveTools(Set<String> registeredToolNames) {
        Objects.requireNonNull(registeredToolNames, "registeredToolNames");
        Set<String> base = new LinkedHashSet<>();
        if (allowedTools.isEmpty()) {
            base.addAll(registeredToolNames);
        } else {
            for (String tool : allowedTools) {
                if (tool != null && !tool.isBlank()) {
                    base.add(tool.trim());
                }
            }
        }
        for (String support : CUSTOM_SUPPORT_TOOLS) {
            if (registeredToolNames.contains(support)) {
                base.add(support);
            }
        }
        for (String blocked : disallowedTools) {
            if (blocked != null && !blocked.isBlank()) {
                base.remove(blocked.trim());
            }
        }
        base.removeAll(RECURSIVE_TOOLS);
        return Set.copyOf(base);
    }

    /**
     * 解析超时秒数：定义优先，否则系统属性 {@code bettercli.subagent.timeout.seconds}，默认 300。
     */
    public int resolveTimeoutSeconds() {
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            return timeoutSeconds;
        }
        String raw = System.getProperty("bettercli.subagent.timeout.seconds");
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 300;
    }
}
