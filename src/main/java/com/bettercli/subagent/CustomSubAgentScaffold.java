package com.bettercli.subagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Custom SubAgent 定义脚手架：在 user/project agents 目录生成 AGENT.md 等文件。
 *
 * <p>仅管理用途；不触发执行。模板内嵌，不依赖网络。
 */
public final class CustomSubAgentScaffold {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    public enum Scope {
        USER, PROJECT
    }

    public record CreateRequest(
            String name,
            Scope scope,
            String templateId,
            boolean force
    ) {
    }

    public record CreateResult(Path agentDir, Path agentMd, String templateId, boolean overwritten) {
    }

    public record Template(String id, String title, String description) {
    }

    private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("blank", new Template("blank", "空白", "最小可运行定义，自行补全职责与工具"));
        TEMPLATES.put("code-reviewer", new Template("code-reviewer", "只读审查", "只读工具审查代码，禁止写文件/执行命令"));
        TEMPLATES.put("researcher", new Template("researcher", "调研", "只读本地探索 + 联网搜索/抓取"));
        TEMPLATES.put("sql-analyzer", new Template("sql-analyzer", "SQL 分析", "慢 SQL / 执行计划建议，只读"));
    }

    private CustomSubAgentScaffold() {
    }

    public static Map<String, Template> templates() {
        return Map.copyOf(TEMPLATES);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * @param userAgentsDir    {@code ~/.bettercli/agents}
     * @param projectAgentsDir {@code .bettercli/agents}
     */
    public static CreateResult create(CreateRequest request, Path userAgentsDir, Path projectAgentsDir)
            throws IOException {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String name = request.name().trim();
        if (!isValidName(name)) {
            throw new IllegalArgumentException(
                    "非法 name：" + name + "（须匹配 ^[A-Za-z][A-Za-z0-9_-]{0,63}$）");
        }
        String templateId = normalizeTemplateId(request.templateId());
        if (!TEMPLATES.containsKey(templateId)) {
            throw new IllegalArgumentException("未知模板：" + request.templateId()
                    + "；可用：" + String.join(", ", TEMPLATES.keySet()));
        }
        Scope scope = request.scope() == null ? Scope.PROJECT : request.scope();
        Path parent = scope == Scope.USER ? userAgentsDir : projectAgentsDir;
        if (parent == null) {
            throw new IllegalArgumentException("agents 目录未配置");
        }
        Path absParent = parent.toAbsolutePath().normalize();
        Path agentDir = absParent.resolve(name).normalize();
        if (!agentDir.startsWith(absParent)) {
            throw new IllegalArgumentException("拒绝路径逃逸: " + agentDir);
        }
        Path agentMd = agentDir.resolve("AGENT.md");
        boolean exists = Files.isRegularFile(agentMd);
        if (exists && !request.force()) {
            throw new IllegalStateException("已存在 " + agentMd + "；加 --force 可覆盖 AGENT.md");
        }

        Files.createDirectories(agentDir);
        Files.writeString(agentMd, renderAgentMd(name, templateId));
        writeIfAbsent(agentDir.resolve("MEMORY.md"), "# Memory\n\n（跨会话保留的偏好与经验，由 write_subagent_memory 追加）\n");
        writeIfAbsent(agentDir.resolve("SOUL.md"), "# Soul\n\n（可选：风格、价值观、沟通偏好）\n");
        writeIfAbsent(agentDir.resolve("IDENTITY.md"), "# Identity\n\n（可选：对外自称与边界）\n");
        return new CreateResult(agentDir, agentMd, templateId, exists);
    }

    /** 解析 {@code /subagent create} 载荷。失败抛 IllegalArgumentException。 */
    public static CreateRequest parseCreatePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException(usage());
        }
        String[] tokens = payload.trim().split("\\s+");
        String name = null;
        Scope scope = Scope.PROJECT;
        String templateId = "blank";
        boolean force = false;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equalsIgnoreCase("--user") || t.equalsIgnoreCase("-u")) {
                scope = Scope.USER;
                continue;
            }
            if (t.equalsIgnoreCase("--project") || t.equalsIgnoreCase("-p")) {
                scope = Scope.PROJECT;
                continue;
            }
            if (t.equalsIgnoreCase("--force") || t.equalsIgnoreCase("-f")) {
                force = true;
                continue;
            }
            if (t.equalsIgnoreCase("--template") || t.equalsIgnoreCase("-t")) {
                if (i + 1 >= tokens.length) {
                    throw new IllegalArgumentException("--template 需要参数；可用："
                            + String.join(", ", TEMPLATES.keySet()));
                }
                templateId = tokens[++i];
                continue;
            }
            if (t.startsWith("-")) {
                throw new IllegalArgumentException("未知选项：" + t + "\n" + usage());
            }
            if (name != null) {
                throw new IllegalArgumentException("多余参数：" + t + "\n" + usage());
            }
            name = t;
        }
        if (name == null) {
            throw new IllegalArgumentException(usage());
        }
        return new CreateRequest(name, scope, templateId, force);
    }

    public static String usage() {
        return "用法: /subagent create <name> [--project|--user] [--template blank|code-reviewer|researcher|sql-analyzer] [--force]\n"
                + "  默认写入 .bettercli/agents/<name>/（--user 写到 ~/.bettercli/agents/）\n"
                + "  /subagent templates 查看模板；/subagent delete <name> --force 删除；创建后自动可 /subagent reload";
    }

    public static String templatesHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("🧩 Custom SubAgent 模板:\n");
        for (Template t : TEMPLATES.values()) {
            sb.append("  - ").append(t.id())
                    .append(" (").append(t.title()).append(") — ")
                    .append(t.description()).append('\n');
        }
        sb.append('\n').append(usage());
        return sb.toString().trim();
    }

    /**
     * 删除 user/project 下的定义目录（不可删 builtin cache）。
     * @param force 必须 true 才执行删除
     */
    public static String delete(String name, boolean force, boolean preferUser,
                                Path userAgentsDir, Path projectAgentsDir,
                                CustomSubAgentRegistry registry) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("用法: /subagent delete <name> --force [--user|--project]");
        }
        if (!isValidName(name.trim())) {
            throw new IllegalArgumentException("非法 name：" + name);
        }
        if (!force) {
            throw new IllegalArgumentException("删除需加 --force。例: /subagent delete " + name.trim() + " --force");
        }
        String n = name.trim();
        Path userDir = userAgentsDir == null ? null : userAgentsDir.resolve(n).toAbsolutePath().normalize();
        Path projectDir = projectAgentsDir == null ? null : projectAgentsDir.resolve(n).toAbsolutePath().normalize();
        Path target = null;
        String scope = null;
        if (preferUser && userDir != null && Files.isDirectory(userDir)) {
            target = userDir;
            scope = "user";
        } else if (!preferUser && projectDir != null && Files.isDirectory(projectDir)) {
            target = projectDir;
            scope = "project";
        } else if (projectDir != null && Files.isDirectory(projectDir)) {
            target = projectDir;
            scope = "project";
        } else if (userDir != null && Files.isDirectory(userDir)) {
            target = userDir;
            scope = "user";
        }
        if (target == null) {
            throw new IllegalStateException("未找到可删除的目录（builtin 不可删）：" + n);
        }
        Path parent = scope.equals("user") ? userAgentsDir.toAbsolutePath().normalize()
                : projectAgentsDir.toAbsolutePath().normalize();
        if (!target.startsWith(parent)) {
            throw new IllegalArgumentException("拒绝路径逃逸: " + target);
        }
        deleteRecursive(target);
        if (registry != null) {
            registry.reload();
        }
        return "✅ 已删除 Custom SubAgent「" + n + "」(" + scope + ")\n   路径: " + target;
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String normalizeTemplateId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "blank";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static void writeIfAbsent(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(path, content);
        }
    }

    private static String renderAgentMd(String name, String templateId) {
        return switch (templateId) {
            case "code-reviewer" -> """
                    ---
                    name: %s
                    description: 只读代码审查：检查正确性、边界条件、安全与可读性问题；不修改文件
                    maxTurns: 25
                    allowedTools: [read_file, grep_code, glob_files, list_dir]
                    disallowedTools: [execute_command, write_file, create_project]
                    ---

                    你是只读代码审查专家（Custom SubAgent）。

                    ## 职责

                    - 根据任务描述审查相关代码，指出具体问题与改进建议
                    - 只使用只读工具核实代码，**绝不**写入文件或执行命令
                    - 输出结构化审查结论：结论摘要、问题列表（严重度）、建议下一步

                    ## 输出格式

                    1. **结论**：通过 / 有条件通过 / 需修改
                    2. **问题**：按严重度（高/中/低）列出，附文件路径与行号（若可知）
                    3. **建议**：可执行的修改建议（由主 Agent 或用户决定是否落地）
                    """.formatted(name);
            case "researcher" -> """
                    ---
                    name: %s
                    description: 调研与资料汇总：本地只读探索 + 联网搜索/抓取；不改仓库
                    maxTurns: 30
                    allowedTools: [read_file, grep_code, glob_files, list_dir, web_search, web_fetch]
                    disallowedTools: [execute_command, write_file, create_project]
                    ---

                    你是调研专家（Custom SubAgent）。

                    ## 职责

                    - 澄清问题后检索本地代码与公开资料
                    - 优先 `glob_files` / `grep_code` / `read_file`；需要外部信息再用 `web_search` / `web_fetch`
                    - **不**修改文件、不执行命令
                    - 输出：要点摘要、证据来源、不确定项、建议下一步

                    ## 输出格式

                    1. **结论**（3–6 条）
                    2. **证据**（本地路径或 URL）
                    3. **缺口 / 风险**
                    4. **建议下一步**
                    """.formatted(name);
            case "sql-analyzer" -> """
                    ---
                    name: %s
                    description: 分析慢 SQL、解释执行计划、指出索引与写法问题；只读不改库
                    maxTurns: 20
                    allowedTools: [read_file, grep_code, glob_files, list_dir]
                    disallowedTools: [execute_command, write_file, create_project]
                    ---

                    你是 SQL 分析专家（Custom SubAgent）。

                    ## 职责

                    - 阅读用户给出的 SQL / 相关代码中的查询语句
                    - 指出性能风险（全表扫、隐式转换、缺失索引、N+1 等）
                    - 给出改写建议与验证思路（EXPLAIN / 索引）
                    - **不**执行命令、不改文件、不连真实数据库

                    ## 输出格式

                    1. **问题摘要**
                    2. **风险列表**（严重度 + 原因）
                    3. **改写建议**
                    4. **验证步骤**
                    """.formatted(name);
            default -> """
                    ---
                    name: %s
                    description: （请改成一句话能力说明，供路由 LLM 与主 Agent 匹配）
                    maxTurns: 30
                    # allowedTools: [read_file, grep_code, glob_files, list_dir]
                    # disallowedTools: [execute_command]
                    # model: glm
                    # timeoutSeconds: 300
                    # skills: []
                    # from: code-reviewer
                    ---

                    你是 Custom SubAgent「%s」。

                    ## 职责

                    - （在此写清你负责什么、不负责什么）

                    ## 输出格式

                    - （约定回复结构，便于主 Agent 汇总）
                    """.formatted(name, name);
        };
    }
}
