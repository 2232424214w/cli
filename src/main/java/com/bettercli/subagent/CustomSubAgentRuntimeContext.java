package com.bettercli.subagent;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Custom SubAgent 执行期上下文：挂在 {@link com.bettercli.tool.ToolRegistry} 上，
 * 供 load_skill 白名单校验与 MEMORY.md 写回定位。主 ReAct 路径不设置，相关工具不可见或失败。
 */
public record CustomSubAgentRuntimeContext(
        String agentName,
        Path memoryFilePath,
        List<String> skillWhitelist
) {
    public CustomSubAgentRuntimeContext {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }
        if (skillWhitelist == null) {
            skillWhitelist = List.of();
        } else {
            skillWhitelist = List.copyOf(skillWhitelist);
        }
    }

    /** 空白名单 = 不限制（继承全量 Skill）；非空 = 仅允许列出的 name。 */
    public boolean allowsSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        if (skillWhitelist.isEmpty()) {
            return true;
        }
        return skillWhitelist.stream().anyMatch(s -> skillName.equalsIgnoreCase(s.trim()));
    }

    public Set<String> skillWhitelistSet() {
        if (skillWhitelist.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(skillWhitelist.stream().map(String::trim).filter(s -> !s.isBlank()).toList());
    }
}
