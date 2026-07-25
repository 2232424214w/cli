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
                    + "   /subagent templates 查看模板；/subagent reload 重新扫描；/subagent status 查看运行中委托\n"
                    + "   任务执行只能由主 Agent 语义识别后调用 run_subagent，不能用斜杠指定";
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
                .append("③ 自然语言点名也可。禁止 /subagent <name> <task> 硬指定执行。\n")
                .append("强制走主 Agent：消息前加 @main 或 /main\n")
                .append("  /subagent create <name> [--template ...] [--user|--project] 生成定义\n")
                .append("  /subagent templates 查看模板\n")
                .append("  /subagent reload 重新扫描\n")
                .append("  /subagent status（/sa-st）查看运行中委托");
        return sb.toString();
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
