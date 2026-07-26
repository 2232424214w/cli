package com.bettercli.cli;

import com.bettercli.subagent.CustomSubAgentDefinition;
import com.bettercli.subagent.CustomSubAgentRegistry;
import com.bettercli.subagent.CustomSubAgentRunStatus;
import com.bettercli.subagent.CustomSubAgentRunner;
import com.bettercli.subagent.CustomSubAgentScaffold;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /subagent 管理命令（list / reload / status / create / templates；不可用斜杠指定执行任务）。
 */
final class SubagentCommandHandler {

    private SubagentCommandHandler() {
    }

    static String list(CustomSubAgentRegistry registry) {
        if (registry == null || registry.all().isEmpty()) {
            return "🧩 Custom Subagents: 未发现定义\n"
                    + "   /subagent create <name> [--template blank|code-reviewer|researcher] 生成脚手架\n"
                    + "   或手动放置 AGENT.md 到 ~/.bettercli/agents/<name>/ 或 .bettercli/agents/<name>/\n"
                    + "   可选同目录 SOUL.md / IDENTITY.md / MEMORY.md\n"
                    + "   任务触发：run_subagent / 路由 / `/subagent:name …`；管理：list|reload|status|create|templates|audit\n"
                    + "   禁止空格形式 `/subagent <name> <task>`";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Custom Subagents (").append(registry.all().size()).append("):\n");
        for (CustomSubAgentDefinition d : registry.all()) {
            sb.append("  - ").append(d.name())
                    .append(" [").append(d.displaySource()).append("]");
            if (d.description() != null && !d.description().isBlank()) {
                sb.append(" — ").append(d.description().trim());
            }
            boolean hasSoul = d.soulMd() != null && !d.soulMd().isBlank();
            boolean hasIdentity = d.identityMd() != null && !d.identityMd().isBlank();
            boolean hasMemory = d.memoryMd() != null && !d.memoryMd().isBlank();
            if (hasSoul || hasIdentity || hasMemory) {
                sb.append(" {");
                if (hasSoul) sb.append("soul ");
                if (hasIdentity) sb.append("identity ");
                if (hasMemory) sb.append("memory");
                sb.append('}');
            }
            sb.append('\n');
        }
        sb.append("\n触发方式：① 主 Agent 语义匹配后调用 run_subagent；")
                .append("② 入站路由 LLM 命中则跳过主 Agent 直达（置信度门控）；")
                .append("③ 消息前缀硬指定 `/subagent:name …` 或 `/sa:name …`（不存在则列出可用）。\n")
                .append("禁止空格形式 `/subagent <name> <task>`（与管理命令冲突，视为未知命令）。\n")
                .append("强制走主 Agent：消息前加 @main 或 /main\n")
                .append("  /subagent create <name> [--template ...] [--user|--project] 生成定义\n")
                .append("  /subagent templates 查看模板\n")
                .append("  /subagent reload 重新扫描\n")
                .append("  /subagent status（/sa-st）查看运行中委托\n")
                .append("  /subagent audit [n] 查看最近审计\n")
                .append("  /sa-l 同 list");
        return sb.toString();
    }

    static String show(CustomSubAgentRegistry registry, String name) {
        if (name == null || name.isBlank()) {
            return "用法: /subagent show <name>";
        }
        if (registry == null) {
            return "🧩 Custom SubAgent 注册表未初始化";
        }
        CustomSubAgentDefinition d = registry.find(name.trim());
        if (d == null) {
            return "❌ 未找到子 Agent \"" + name.trim() + "\"\n" + list(registry);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Custom SubAgent「").append(d.name()).append("」\n");
        sb.append("  source: ").append(d.displaySource()).append('\n');
        if (d.extendsFrom() != null) {
            sb.append("  from: ").append(d.extendsFrom()).append('\n');
        }
        sb.append("  description: ").append(d.description() == null ? "" : d.description()).append('\n');
        if (d.model() != null && !d.model().isBlank()) {
            sb.append("  model: ").append(d.model()).append('\n');
        }
        if (d.maxTurns() != null) {
            sb.append("  maxTurns: ").append(d.maxTurns()).append('\n');
        }
        sb.append("  timeoutSeconds: ").append(d.resolveTimeoutSeconds()).append('\n');
        if (!d.allowedTools().isEmpty()) {
            sb.append("  allowedTools: ").append(d.allowedTools()).append('\n');
        }
        if (!d.disallowedTools().isEmpty()) {
            sb.append("  disallowedTools: ").append(d.disallowedTools()).append('\n');
        }
        if (!d.skills().isEmpty()) {
            sb.append("  skills: ").append(d.skills()).append('\n');
        }
        if (d.agentMdPath() != null) {
            sb.append("  path: ").append(d.agentMdPath()).append('\n');
        }
        boolean hasSoul = d.soulMd() != null && !d.soulMd().isBlank();
        boolean hasIdentity = d.identityMd() != null && !d.identityMd().isBlank();
        boolean hasMemory = d.memoryMd() != null && !d.memoryMd().isBlank();
        sb.append("  sidecars: soul=").append(hasSoul)
                .append(" identity=").append(hasIdentity)
                .append(" memory=").append(hasMemory).append('\n');
        String body = d.body() == null ? "" : d.body().trim();
        if (body.length() > 800) {
            body = body.substring(0, 800) + "\n…";
        }
        sb.append("\n--- AGENT body ---\n").append(body);
        return sb.toString();
    }

    static String sessions(CustomSubAgentRunner runner, String payload) {
        int limit = 20;
        if (payload != null && !payload.isBlank()) {
            try {
                limit = Integer.parseInt(payload.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (runner == null) {
            return "🧩 Custom SubAgent 运行态: 未初始化";
        }
        return runner.sessionStore().formatList(limit);
    }

    static String stats() {
        return com.bettercli.subagent.CustomSubAgentAudit.formatStats();
    }

    static String delete(String payload, Path userAgentsDir, Path projectAgentsDir,
                         CustomSubAgentRegistry registry) {
        try {
            if (payload == null || payload.isBlank()) {
                return "❌ 用法: /subagent delete <name> --force [--user|--project]";
            }
            String[] tokens = payload.trim().split("\\s+");
            String name = null;
            boolean force = false;
            boolean preferUser = false;
            boolean preferProject = false;
            for (String t : tokens) {
                if (t.equalsIgnoreCase("--force") || t.equalsIgnoreCase("-f")) {
                    force = true;
                } else if (t.equalsIgnoreCase("--user") || t.equalsIgnoreCase("-u")) {
                    preferUser = true;
                } else if (t.equalsIgnoreCase("--project") || t.equalsIgnoreCase("-p")) {
                    preferProject = true;
                } else if (t.startsWith("-")) {
                    return "❌ 未知选项: " + t;
                } else if (name == null) {
                    name = t;
                } else {
                    return "❌ 多余参数: " + t;
                }
            }
            boolean preferUserFinal = preferUser && !preferProject;
            return CustomSubAgentScaffold.delete(name, force, preferUserFinal,
                    userAgentsDir, projectAgentsDir, registry);
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    static String audit(int limit) {
        return com.bettercli.subagent.CustomSubAgentAudit.formatTail(limit);
    }

    static String status(CustomSubAgentRunner runner) {
        if (runner == null) {
            return "🧩 Custom SubAgent 运行态: 未初始化";
        }
        List<CustomSubAgentRunStatus> runs = runner.activeRuns();
        if (runs.isEmpty()) {
            return "🧩 Custom SubAgent 运行态: 当前无正在执行的委托";
        }
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Custom SubAgent 运行中 (").append(runs.size()).append("):\n");
        for (CustomSubAgentRunStatus run : runs) {
            long sec = Duration.between(run.startedAt(), now).getSeconds();
            sb.append("  - ").append(run.agentName())
                    .append(" session=").append(run.childSessionId())
                    .append(" parent=").append(run.parentSessionId())
                    .append(" elapsed=").append(sec).append('s')
                    .append('\n');
            if (run.taskPreview() != null && !run.taskPreview().isBlank()) {
                sb.append("    task: ").append(run.taskPreview()).append('\n');
            }
            if (run.lastProgress() != null && !run.lastProgress().isBlank()) {
                sb.append("    progress: ").append(run.lastProgress()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    static String templates() {
        return CustomSubAgentScaffold.templatesHelp();
    }

    /**
     * 生成脚手架并 reload registry。
     *
     * @return 给用户看的结果文本
     */
    static String create(String payload, Path userAgentsDir, Path projectAgentsDir,
                         CustomSubAgentRegistry registry) {
        try {
            CustomSubAgentScaffold.CreateRequest req = CustomSubAgentScaffold.parseCreatePayload(payload);
            CustomSubAgentScaffold.CreateResult result =
                    CustomSubAgentScaffold.create(req, userAgentsDir, projectAgentsDir);
            if (registry != null) {
                registry.reload();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(result.overwritten() ? "♻️ 已覆盖" : "✅ 已创建")
                    .append(" Custom SubAgent「").append(req.name()).append("」\n")
                    .append("   模板: ").append(result.templateId()).append('\n')
                    .append("   目录: ").append(result.agentDir()).append('\n')
                    .append("   定义: ").append(result.agentMd()).append('\n')
                    .append("   作用域: ").append(req.scope() == CustomSubAgentScaffold.Scope.USER
                            ? "user (~/.bettercli/agents)" : "project (.bettercli/agents)").append('\n')
                    .append("已重新扫描；下一轮即可被主 Agent / 路由匹配。\n")
                    .append("提示：请编辑 description 与正文，路由与委托都依赖准确描述。\n")
                    .append("任务仍须语义触发（run_subagent / 路由），不能用 /subagent <name> <task>。");
            return sb.toString();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            return "❌ 创建失败: " + e.getMessage();
        }
    }

    static String startupSummary(CustomSubAgentRegistry registry) {
        if (registry == null || registry.all().isEmpty()) {
            return "";
        }
        return "Custom Subagent " + registry.all().size();
    }
}
