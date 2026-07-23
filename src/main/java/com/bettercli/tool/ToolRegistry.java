package com.bettercli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bettercli.browser.BrowserAuditMetadata;
import com.bettercli.browser.BrowserCheckResult;
import com.bettercli.browser.BrowserConnector;
import com.bettercli.browser.BrowserGuard;
import com.bettercli.context.ContextProfile;
import com.bettercli.lsp.LspDiagnosticReport;
import com.bettercli.lsp.LspManager;
import com.bettercli.mcp.protocol.McpToolDescriptor;
import com.bettercli.rag.CodeRetriever;
import com.bettercli.rag.SearchResultFormatter;
import com.bettercli.rag.VectorStore;
import com.bettercli.policy.AuditLog;
import com.bettercli.policy.CommandGuard;
import com.bettercli.policy.PathGuard;
import com.bettercli.policy.PolicyException;
import com.bettercli.prompt.ProjectMemoryLoader;
import com.bettercli.memory.AgentMemoryEntry;
import com.bettercli.memory.AgentMemoryStore;
import com.bettercli.memory.MemoryEntryPatch;
import com.bettercli.memory.MemoryListQuery;
import com.bettercli.memory.MemorySearchQuery;
import com.bettercli.memory.MemorySearchResult;
import com.bettercli.memory.SessionMessage;
import com.bettercli.memory.SessionMessageSearchQuery;
import com.bettercli.memory.SessionMessageSearchResult;
import com.bettercli.memory.SessionMessageStore;
import com.bettercli.runtime.CancellationContext;
import com.bettercli.snapshot.RestoreResult;
import com.bettercli.snapshot.SnapshotService;
import com.bettercli.skill.Skill;
import com.bettercli.skill.SkillContextBuffer;
import com.bettercli.skill.SkillRegistry;
import com.bettercli.web.FetchResult;
import com.bettercli.web.HtmlExtractor;
import com.bettercli.web.NetworkPolicy;
import com.bettercli.web.SearchProvider;
import com.bettercli.web.SearchProviderFactory;
import com.bettercli.web.SearchResult;
import com.bettercli.web.WebFetcher;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具注册表 - 管理所有可用工具
 */
public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    private static final int MAX_READ_FILE_LINES = 2_000;
    private static final int MAX_GREP_RESULTS = 200;
    private static final int MAX_GREP_CONTEXT_LINES = 5;
    private static final int DEFAULT_GREP_MAX_CHARS = 24_000;
    private static final int MAX_GREP_MAX_CHARS = 60_000;
    private static final int DEFAULT_GREP_HEAD_LIMIT = 20;
    private static final String STEP_SEARCH_SERVER = "step_search";
    private static final String STEP_SEARCH_TOOL = "mcp__" + STEP_SEARCH_SERVER + "__web_search";
    private static final String STEP_FETCH_TOOL = "mcp__" + STEP_SEARCH_SERVER + "__web_fetch";
    private static final Set<String> SEARCH_EXCLUDED_DIRS = Set.of(
            ".git", ".bettercli", "target", "node_modules", "dist", "build", "coverage", ".idea", ".gradle"
    );
    // write_file 单次写入字节数上限。LLM 想塞超大内容时通常是误生成（重复粘贴 / hallucinate 大段日志），
    // 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;
    // 需要审计的内置工具（与 ApprovalPolicy 的 DANGEROUS_TOOLS 保持一致）；MCP 工具按前缀动态纳入审计。
    private static final Set<String> AUDIT_TOOLS = Set.of("write_file", "execute_command", "create_project", "revert_turn");
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, McpRegisteredTool> mcpTools = new ConcurrentHashMap<>();
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    private String projectPath = System.getProperty("user.dir");
    private PathGuard pathGuard = new PathGuard(projectPath);
    private final AuditLog auditLog = new AuditLog();
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;
    private ContextProfile contextProfile = ContextProfile.from(null);
    private BrowserGuard browserGuard;
    private BrowserConnector browserConnector;
    private BiConsumer<String, String> memorySaver;
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private java.util.function.BiConsumer<String, String[]> writeFileObserver = (p, ba) -> {};
    private LspManager lspManager = new LspManager(projectPath);
    private SnapshotService snapshotService = SnapshotService.forProject(Path.of(projectPath));
    private boolean customSnapshotService;
    private ProjectMemoryLoader projectMemoryLoader = ProjectMemoryLoader.createDefault(Path.of(projectPath));
    private AgentMemoryStore agentMemoryStore;
    private SessionMessageStore sessionMessageStore;
    // ReAct 轻量规划存储（对标 Claude Code TodoWrite）。由 Agent 在构造后注入；
    // 未注入时 update_plan 工具返回未初始化提示，不影响其它工具。
    private com.bettercli.agent.PlanStore planStore;
    // Multi-Agent 共享黑板（阶段C/D）。由 AgentOrchestrator 在派活时注入当前 worker 名；
    // 未注入时 ask_peer 工具返回未初始化提示。主 ReAct Agent 不注入，故 ask_peer 对 ReAct 不可用。
    private com.bettercli.agent.SharedState sharedState;
    private volatile String currentWorkerName = "";
    private volatile String currentProvider = "";
    private volatile String currentModel = "";

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        // 注册内置工具
        registerFileTools();
        registerShellTools();
        registerCodeTools();
        registerRagTools();
        registerWebTools();
        registerBrowserTools();
        registerMemoryTools();
        registerSkillTools();
        registerSnapshotTools();
        registerPaiMdTools();
        registerAgentMemoryTools();
        registerSessionSearchTool();
        registerPlanTool();
        registerPeerTool();
    }

    /**
     * 设置代码检索的项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        this.pathGuard = new PathGuard(projectPath);
        this.lspManager.setProjectPath(projectPath);
        if (!customSnapshotService) {
            this.snapshotService.close();
            this.snapshotService = SnapshotService.forProject(Path.of(projectPath));
        }
        this.projectMemoryLoader = ProjectMemoryLoader.createDefault(Path.of(projectPath));
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    public void setContextProfile(ContextProfile contextProfile) {
        if (contextProfile != null) {
            this.contextProfile = contextProfile;
        }
    }

    public ContextProfile getContextProfile() {
        return contextProfile;
    }

    public void setCurrentModel(String provider, String model) {
        this.currentProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        this.currentModel = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    public void setBrowserGuard(BrowserGuard browserGuard) {
        this.browserGuard = browserGuard;
    }

    protected BrowserGuard getBrowserGuard() {
        return browserGuard;
    }

    public void setBrowserConnector(BrowserConnector browserConnector) {
        this.browserConnector = browserConnector;
    }

    public void setMemorySaver(Consumer<String> memorySaver) {
        this.memorySaver = memorySaver == null ? null : (fact, scope) -> memorySaver.accept(fact);
    }

    public void setScopedMemorySaver(BiConsumer<String, String> memorySaver) {
        this.memorySaver = memorySaver;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    public SkillContextBuffer getSkillContextBuffer() {
        return skillContextBuffer;
    }

    /**
     * 注册 write_file 写入观察者：参数 (path, [before, after])，
     * before == null 表示新建文件或读不出原文。
     * 用于把 write_file 接到行内 diff 渲染等只读副作用里；
     * 观察者抛异常不影响 write_file 主路径。
     */
    public void setWriteFileObserver(java.util.function.BiConsumer<String, String[]> observer) {
        this.writeFileObserver = observer == null ? (p, ba) -> {} : observer;
    }

    public void setLspManager(LspManager lspManager) {
        this.lspManager = lspManager == null ? new LspManager(projectPath) : lspManager;
        this.lspManager.setProjectPath(projectPath);
    }

    public LspDiagnosticReport flushPendingLspDiagnostics() {
        return lspManager == null ? LspDiagnosticReport.EMPTY : lspManager.flushPendingDiagnostics();
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService == null ? SnapshotService.forProject(Path.of(projectPath)) : snapshotService;
        this.customSnapshotService = snapshotService != null;
    }

    /**
     * 注入自定义 BETTER.md 加载器；默认按 projectPath 自动构造。
     * 主要供测试或需要固定 userConfigDir / 递归发现行为的场景使用。
     */
    public void setProjectMemoryLoader(ProjectMemoryLoader projectMemoryLoader) {
        this.projectMemoryLoader = projectMemoryLoader == null
                ? ProjectMemoryLoader.createDefault(Path.of(projectPath))
                : projectMemoryLoader;
    }

    public ProjectMemoryLoader getProjectMemoryLoader() {
        return projectMemoryLoader;
    }

    /**
     * 注入 Agent 维护的长期记忆存储（对标美团 agent_memory 表）。
     * 未注入时 agent_memory_* 工具会返回"未初始化"提示，不影响其他工具。
     */
    public void setAgentMemoryStore(AgentMemoryStore agentMemoryStore) {
        this.agentMemoryStore = agentMemoryStore;
    }

    public AgentMemoryStore getAgentMemoryStore() {
        return agentMemoryStore;
    }

    public void setSessionMessageStore(SessionMessageStore sessionMessageStore) {
        this.sessionMessageStore = sessionMessageStore;
    }

    public SessionMessageStore getSessionMessageStore() {
        return sessionMessageStore;
    }

    /**
     * 注入 ReAct 轻量规划存储。由 Agent 在构造后调用，使 update_plan 工具
     * 能读写当前会话的 plan。未注入时 update_plan 返回未初始化提示。
     */
    public void setPlanStore(com.bettercli.agent.PlanStore planStore) {
        this.planStore = planStore;
    }

    public com.bettercli.agent.PlanStore getPlanStore() {
        return planStore;
    }

    /**
     * 注入 Multi-Agent 共享黑板与当前 worker 名（阶段D p2p）。
     * 由 AgentOrchestrator 在派活时调用：每派一个 worker 执行前 setSharedState + setCurrentWorkerName，
     * 使该 worker 的 ask_peer 工具能读写黑板 peer 通道。主 ReAct Agent 不调用，故 ask_peer 对 ReAct 不可用。
     */
    public void setSharedState(com.bettercli.agent.SharedState sharedState) {
        this.sharedState = sharedState;
    }

    public void setCurrentWorkerName(String name) {
        this.currentWorkerName = name == null ? "" : name;
    }

    public com.bettercli.agent.SharedState getSharedState() {
        return sharedState;
    }

    /**
     * 获取 Agent 记忆的启动摘要（用于注入 system prompt）。
     * 返回最近 maxEntries 条 active 记忆的精简摘要，硬上限 maxChars 字符。
     * 供 Agent.java / PlanExecuteAgent.java 的 buildProjectMemoryContext 调用。
     */
    public String getAgentMemorySummary(int maxEntries, int maxChars) {
        if (agentMemoryStore == null) {
            return "";
        }
        try {
            List<AgentMemoryEntry> recent = agentMemoryStore.list(MemoryListQuery.builder()
                    .status(AgentMemoryEntry.MemoryStatus.ACTIVE)
                    .limit(maxEntries)
                    .orderBy("created_at")
                    .build());
            if (recent.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("### Agent 维护的记忆摘要（最近 ").append(recent.size()).append(" 条）\n\n");
            int currentSize = sb.length();
            for (AgentMemoryEntry entry : recent) {
                String line = formatMemorySummaryLine(entry);
                if (currentSize + line.length() > maxChars) {
                    sb.append("...（已达到 ").append(maxChars).append(" 字符上限，更多记忆可用 agent_memory_search 检索）\n");
                    break;
                }
                sb.append(line);
                currentSize += line.length();
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatMemorySummaryLine(AgentMemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(entry.getType()).append("] ");
        String content = entry.getContent();
        if (content.length() > 120) {
            content = content.substring(0, 120) + "...";
        }
        sb.append(content);
        if (!entry.getKeywords().isEmpty()) {
            sb.append(" (").append(String.join("/", entry.getKeywords())).append(")");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 注册会话历史检索工具（对标美团 1024 Agent session_search）。
     * 五阶段管道：BM25 检索 → 按会话分组 → 加载完整 → 截断预览 → 返回。
     */
    private void registerSessionSearchTool() {
        tools.put("session_search", new Tool(
                "session_search",
                "检索历史会话消息。当用户问\"之前怎么处理过 X\"、或需要回溯历史决策时调用。"
                        + "BM25 全文检索，按会话分组返回相关历史对话片段。"
                        + "当前项目作用域内检索，默认回溯 30 天。",
                createParameters(
                        new Param("query", "string", "检索查询（关键词或自然语言）", true),
                        new Param("limit", "integer", "返回会话数，默认 3，最多 10", false),
                        new Param("role_filter", "string", "user / assistant，默认全部", false),
                        new Param("days_back", "integer", "回溯天数，默认 30，最大 365", false)
                ),
                args -> sessionSearch(args)
        ));
    }

    private String sessionSearch(Map<String, String> args) {
        if (sessionMessageStore == null) {
            return "session_search 失败: 会话消息存储未初始化";
        }
        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "session_search 失败: query 不能为空";
        }
        int limit = clamp(parseInt(args.get("limit"), 3), 1, 10);
        String roleFilter = args.get("role_filter");
        if (roleFilter != null && roleFilter.isBlank()) roleFilter = null;
        int daysBack = clamp(parseInt(args.get("days_back"), 30), 1, 365);

        java.time.Instant since = java.time.Instant.now().minusSeconds((long) daysBack * 86400);

        SessionMessageSearchQuery searchQuery = SessionMessageSearchQuery.builder()
                .query(query.trim())
                .limit(limit)
                .roleFilter(roleFilter)
                .project(projectPath)
                .since(since)
                .build();

        try {
            java.util.List<SessionMessageSearchResult> results = sessionMessageStore.search(searchQuery);
            if (results.isEmpty()) {
                return "未找到匹配的历史会话（query=" + query + ", days_back=" + daysBack + "）";
            }
            return formatSessionSearchResults(query, results);
        } catch (Exception e) {
            return "session_search 检索失败: " + e.getMessage();
        }
    }

    private static String formatSessionSearchResults(String query, java.util.List<SessionMessageSearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 历史会话检索: ").append(query).append("\n");
        sb.append("找到 ").append(results.size()).append(" 个相关会话:\n\n");
        for (int i = 0; i < results.size(); i++) {
            SessionMessageSearchResult r = results.get(i);
            sb.append("## 会话 #").append(i + 1).append(": ").append(r.getConversationId()).append("\n");
            sb.append("- 消息数: ").append(r.getTotalMessages()).append("\n");
            sb.append("- BM25 分: ").append(String.format("%.3f", r.getBestBm25Score())).append("\n");
            sb.append("- 命中消息:\n");
            for (SessionMessageSearchResult.MatchedMessage m : r.getMatchedMessages()) {
                sb.append("  [").append(m.getRole()).append("] ").append(m.getPreview()).append("\n");
            }
            sb.append("\n").append(r.formatConversationPreview()).append("\n\n---\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("offset", "integer", "起始行号，1 表示第一行；省略时读取全文", false),
                        new Param("limit", "integer", "最多读取多少行；省略时读取全文，最大 2000 行", false)
                ),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        return readFileForTool(safe, args);
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > MAX_WRITE_FILE_BYTES) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + "MB 上限");
                    }
                    Path safe = pathGuard.resolveSafe(path);
                    String before = null;
                    try {
                        if (Files.exists(safe) && Files.isRegularFile(safe)) {
                            before = Files.readString(safe);
                        }
                    } catch (Exception ignored) {
                        // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
                    }
                    try {
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);
                        try {
                            writeFileObserver.accept(path, new String[]{before, content});
                        } catch (Exception ignored) {
                            // observer 失败不能影响 write_file 主路径
                        }
                        runPostEditLspHook(path, safe);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    Path safe = pathGuard.resolveSafe(args.get("path"));
                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            return "目录为空或不存在";
                        }
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        for (File f : files) {
                            sb.append(f.isDirectory() ? "[D] " : "[F] ")
                              .append(f.getName())
                              .append("\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));

        tools.put("glob_files", new Tool(
                "glob_files",
                "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java",
                createParameters(
                        new Param("pattern", "string", "glob 模式，例如 **/*.java、**/*Controller*、README.md", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("max_results", "integer", "最多返回结果数，默认 50，上限 200", false)
                ),
                args -> globFiles(args)
        ));

        tools.put("grep_code", new Tool(
                "grep_code",
                "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文",
                createParameters(
                        new Param("pattern", "string", "要搜索的关键字或正则", true),
                        new Param("path", "string", "搜索起始目录，默认 .", false),
                        new Param("glob", "string", "可选文件 glob 过滤，例如 **/*.java", false),
                        new Param("regex", "boolean", "是否按 Java 正则解释 pattern，默认 false 表示字面量搜索", false),
                        new Param("case_sensitive", "boolean", "是否大小写敏感，默认 true", false),
                        new Param("context_lines", "integer", "每条命中前后上下文行数，默认 0，上限 5", false),
                        new Param("max_results", "integer", "最多返回命中数，默认 50，上限 200", false),
                        new Param("head_limit", "integer", "单个文件最多返回多少条命中，默认 20，上限 50", false),
                        new Param("max_chars", "integer", "单次工具结果字符预算，默认 24000，上限 60000", false)
                ),
                args -> grepCode(args)
        ));
    }

    private String readFileForTool(Path file, Map<String, String> args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return "读取文件失败: 不是普通文件";
        }
        boolean ranged = args.containsKey("offset") || args.containsKey("limit");
        if (!ranged) {
            return "文件内容:\n" + Files.readString(file);
        }

        int offset = Math.max(1, parseInt(args.get("offset"), 1));
        int limit = Math.max(1, Math.min(parseInt(args.get("limit"), 200), MAX_READ_FILE_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围";
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return sb.toString().trim();
    }

    private String globFiles(Map<String, String> args) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "文件匹配失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        Path projectRoot = pathGuard.getRootPath();
        PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem().getPathMatcher("glob:" + normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SearchFileVisitor(projectRoot, path -> {
                if (matches.size() >= maxResults) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    matches.add(relative.toString());
                }
            }));
        } catch (Exception e) {
            return "文件匹配失败: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "未找到匹配文件: " + pattern;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String grepCode(Map<String, String> args) {
        String query = args.get("pattern");
        if (query == null || query.isBlank()) {
            return "代码搜索失败: pattern 不能为空";
        }
        Path root = pathGuard.resolveSafe(args.getOrDefault("path", "."));
        Path projectRoot = pathGuard.getRootPath();
        int maxResults = clamp(parseInt(args.get("max_results"), 50), 1, MAX_GREP_RESULTS);
        int contextLines = clamp(parseInt(args.get("context_lines"), 0), 0, MAX_GREP_CONTEXT_LINES);
        boolean regex = parseBoolean(args.get("regex"), false);
        boolean caseSensitive = parseBoolean(args.get("case_sensitive"), true);
        int headLimit = clamp(parseInt(args.get("head_limit"), DEFAULT_GREP_HEAD_LIMIT), 1, 50);
        int maxChars = clamp(parseInt(args.get("max_chars"), DEFAULT_GREP_MAX_CHARS), 1_000, MAX_GREP_MAX_CHARS);
        CodeSearchRequest request = new CodeSearchRequest(
                query,
                root,
                projectRoot,
                args.get("glob"),
                regex,
                caseSensitive,
                contextLines,
                maxResults,
                headLimit
        );
        CodeSearchResult result = new RipgrepCodeSearchEngine(SEARCH_EXCLUDED_DIRS).search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return "代码搜索失败: " + result.partialReason();
        }
        if (result.matches().isEmpty()) {
            return "未找到匹配内容: " + query;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return sb.toString().trim();
    }

    private void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }

    /**
     * 注册Shell命令工具
     */
    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> executeCommand(args.get("command"))
        ));
    }

    /**
     * 注册代码相关工具
     */
    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    Path projectRoot = pathGuard.resolveSafe(name);
                    try {
                        Files.createDirectories(projectRoot);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectRoot.resolve("src/main/java"));
                                Files.createDirectories(projectRoot.resolve("src/main/resources"));
                                Files.writeString(projectRoot.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectRoot.resolve(name));
                                Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectRoot.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册 RAG 检索工具
     */
    private void registerRagTools() {
        tools.put("search_code", new Tool(
                "search_code",
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块；精确符号/字符串定位请优先用 grep_code/glob_files/read_file；默认 top_k=5，可显式指定（上限 30）",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new Param("top_k", "integer", "返回结果数量（默认 5，上限 30）", false)
                ),
                args -> {
                    String query = args.get("query");
                    int topK = 5;
                    try {
                        if (args.containsKey("top_k")) {
                            topK = Integer.parseInt(args.get("top_k"));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    topK = Math.max(1, Math.min(topK, 30));

                    try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
                        var stats = retriever.getStats();
                        if (stats.chunkCount() == 0) {
                            return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
                        }

                        List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
                        if (results.isEmpty()) {
                            return "未找到与查询相关的代码。";
                        }

                        return SearchResultFormatter.formatForTool(query, results);
                    } catch (Exception e) {
                        return "代码检索失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册联网工具：web_search（多 provider 抽象）+ web_fetch（HTTP + readability）
     */
    private void registerWebTools() {
        tools.put("web_search", new Tool(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。" +
                        "支持 SerpAPI（默认）和 SearXNG（自托管）两种 provider，由 SEARCH_PROVIDER 环境变量切换。",
                createParameters(
                        new Param("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new Param("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5))
        ));

        tools.put("web_fetch", new Tool(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。" +
                        "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。",
                createParameters(
                        new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
                ),
                args -> webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS))
        ));
    }

    private void registerBrowserTools() {
        tools.put("browser_connect", new Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                        : browserConnector.connectDefault()
        ));
        tools.put("browser_disconnect", new Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法切回 isolated 模式"
                        : browserConnector.disconnect()
        ));
        tools.put("browser_status", new Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                createParameters(),
                args -> browserConnector == null
                        ? "浏览器连接器未初始化，无法查看浏览器状态"
                        : browserConnector.status()
        ));
    }

    private void registerSkillTools() {
        tools.put("load_skill", new Tool(
                "load_skill",
                "Load full SKILL.md instructions for a skill the system has indexed (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description matches the current task. Pass the exact kebab-case skill name. The full body will appear at the start of your next user message under \"## 已加载 Skill：<name>\". Don't reload the same skill twice in one session.",
                createParameters(new Param("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                args -> {
                    String name = args.get("name");
                    if (name == null || name.isBlank()) {
                        return "load_skill 失败: name 不能为空";
                    }
                    if (skillRegistry == null) {
                        return "load_skill 失败: Skill 系统未初始化";
                    }
                    Skill skill = skillRegistry.findSkill(name);
                    if (skill == null) {
                        Skill any = skillRegistry.findAnySkill(name);
                        if (any == null) {
                            return "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill";
                        }
                        return "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用";
                    }
                    String body = skill.body();
                    int originalLen = body == null ? 0 : body.length();
                    int max = 5 * 1024;
                    String injected = body == null ? "" : body;
                    if (injected.length() > max) {
                        injected = injected.substring(0, max)
                                + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
                    }
                    if (skillContextBuffer != null) {
                        skillContextBuffer.push(name, injected);
                    }
                    return "已加载 skill '" + name + "' 的完整指引（" + originalLen
                            + " bytes），将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。";
                }
        ));
    }

    private void registerMemoryTools() {
        tools.put("save_memory", new Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；scope 默认 project，跨项目偏好才用 global；不要保存一次性任务请求、临时文件名或模型猜测。",
                createParameters(
                        new Param("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true),
                        new Param("scope", "string", "记忆作用域：project 或 global。默认 project；跨项目长期偏好才用 global", false)
                ),
                args -> {
                    String fact = args.get("fact");
                    if (fact == null || fact.isBlank()) {
                        return "保存长期记忆失败: fact 不能为空";
                    }
                    if (memorySaver == null) {
                        return "保存长期记忆失败: 记忆保存器未初始化";
                    }
                    String normalized = fact.trim();
                    String scope = "global".equalsIgnoreCase(args.get("scope")) ? "global" : "project";
                    memorySaver.accept(normalized, scope);
                    return "💾 已保存到长期记忆(" + scope + "): " + normalized;
                }
        ));
    }

    private void registerSnapshotTools() {
        tools.put("revert_turn", new Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                createParameters(new Param("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)),
                args -> {
                    int offset = parseInt(args.get("offset"), 1);
                    try {
                        RestoreResult result = snapshotService.restorePreTurn(Math.max(1, offset));
                        return result.formatForCli();
                    } catch (Exception e) {
                        return "恢复快照失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册 BETTER.md 项目记忆相关工具（对标美团 MEMORY.md + Claude Code CLAUDE.md）。
     * - read_better_md：Agent 主动读取 BETTER.md 完整内容 + 容量状态，用于在 suggest_better_md 前确认现状
     * - suggest_better_md：Agent 提出建议条目，经 HITL 用户确认后追加到 BETTER.md
     */
    private void registerPaiMdTools() {
        tools.put("read_better_md", new Tool(
                "read_better_md",
                "读取当前项目已加载的 BETTER.md 完整内容（含用户级 / 项目级 / 本地覆盖 / 向上递归发现的祖先 BETTER.md）"
                        + "并返回容量状态。建议在调用 suggest_better_md 之前先调用此工具确认现状，避免重复添加已有条目或超出容量上限。",
                createParameters(
                        new Param("summary", "boolean", "是否只返回容量摘要而不返回完整内容，默认 false 返回完整内容", false)
                ),
                args -> readPaiMd(parseBoolean(args.get("summary"), false))
        ));

        tools.put("suggest_better_md", new Tool(
                "suggest_better_md",
                "向 BETTER.md 项目记忆文件建议一条新条目；调用前应先 read_better_md 确认现状。"
                        + "本工具会经 HITL 用户确认（可批准 / 修改 / 拒绝 / 跳过）后再追加到目标 BETTER.md。"
                        + "建议内容必须精炼、稳定、可跨会话复用（如团队规范、架构约束、常用命令），不要写入一次性任务请求。",
                createParameters(
                        new Param("suggestion", "string", "要追加到 BETTER.md 的建议条目（单行或多行 markdown 片段，会原样追加）", true),
                        new Param("target", "string", "写入目标文件路径；省略时由 loader 自动选择（优先项目级 BETTER.md）", false),
                        new Param("reason", "string", "建议原因 / 上下文，便于用户在 HITL 面板理解为何要加这条", false)
                ),
                args -> suggestPaiMd(args)
        ));
    }

    private String readPaiMd(boolean summaryOnly) {
        ProjectMemoryLoader loader = projectMemoryLoader;
        if (loader == null) {
            return "BETTER.md 加载器未初始化";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(loader.getCapacityStatus()).append("\n");
        List<Path> loadedFiles = loader.getLoadedFiles();
        if (loadedFiles.isEmpty()) {
            sb.append("\n当前未加载任何 BETTER.md 文件。");
        } else {
            sb.append("\n已加载 BETTER.md 文件 ").append(loadedFiles.size()).append(" 个:");
            for (int i = 0; i < loadedFiles.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(loadedFiles.get(i));
            }
        }
        if (summaryOnly) {
            return sb.toString().trim();
        }
        String content = loader.readContent();
        if (content == null || content.isBlank()) {
            return sb.append("\n\n（BETTER.md 内容为空）").toString().trim();
        }
        sb.append("\n\n--- BETTER.md 内容 ---\n").append(content);
        return sb.toString().trim();
    }

    private String suggestPaiMd(Map<String, String> args) {
        ProjectMemoryLoader loader = projectMemoryLoader;
        if (loader == null) {
            return "BETTER.md 加载器未初始化";
        }
        String suggestion = args.get("suggestion");
        if (suggestion == null || suggestion.isBlank()) {
            return "suggest_better_md 失败: suggestion 不能为空";
        }
        suggestion = suggestion.trim();
        // 容量护栏：超上限直接拒绝，提示先整合
        if (loader.isOverLimit()) {
            return "suggest_better_md 失败: " + loader.getCapacityStatus()
                    + "\n请先整合已有条目（合并相似规则、删除过时条目）后再添加新条目。"
                    + "可调用 read_better_md 查看现有内容。";
        }
        // 决定写入目标
        Path target;
        String targetArg = args.get("target");
        if (targetArg != null && !targetArg.isBlank()) {
            target = pathGuard.resolveSafe(targetArg);
        } else {
            target = loader.getSuggestTarget();
        }
        if (target == null) {
            return "suggest_better_md 失败: 无法确定写入目标";
        }
        // 确保父目录存在（例如 .bettercli/BETTER.md）
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            return "suggest_better_md 失败: 创建目标目录失败 - " + e.getMessage();
        }
        // 追加到目标文件（保留原有内容，末尾追加换行 + 新条目）
        String reason = args.get("reason");
        try {
            String existing = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
            StringBuilder newContent = new StringBuilder();
            if (existing == null || existing.isBlank()) {
                // 文件不存在或为空，初始化为 BETTER.md 头部 + 新条目
                newContent.append("# BETTER.md\n\n").append(suggestion).append("\n");
            } else {
                newContent.append(existing);
                if (!existing.endsWith("\n")) {
                    newContent.append("\n");
                }
                newContent.append("\n").append(suggestion).append("\n");
            }
            Files.writeString(target, newContent.toString(), StandardCharsets.UTF_8);
            String result = "✅ 已追加到 BETTER.md: " + pathGuard.getRootPath().relativize(target)
                    + "\n新增条目: " + (suggestion.length() > 100 ? suggestion.substring(0, 100) + "..." : suggestion);
            if (reason != null && !reason.isBlank()) {
                result += "\n原因: " + reason;
            }
            // 刷新 loader 以反映新容量
            ProjectMemoryLoader refreshed = ProjectMemoryLoader.createDefault(Path.of(projectPath));
            result += "\n" + refreshed.getCapacityStatus();
            return result;
        } catch (Exception e) {
            return "suggest_better_md 失败: 写入文件失败 - " + e.getMessage();
        }
    }

    /**
     * 注册 Agent 维护的长期记忆工具（对标美团 1024 Agent agent_memory 表 + Agentic RAG）。
     * - agent_memory_search：Agent 主动检索记忆（BM25 + confidence 加权）
     * - agent_memory_save：Agent 自主保存记忆（confidence 门槛 + 敏感词拦截）
     * - agent_memory_update：Agent 更新已有记忆
     * - agent_memory_delete：Agent 删除过时记忆
     *
     * 这 4 个工具不走 HITL（Agent 自主决策），但保存时会做敏感词拦截和 confidence 门槛检查。
     */
    private void registerAgentMemoryTools() {
        tools.put("agent_memory_search", new Tool(
                "agent_memory_search",
                "检索 Agent 维护的长期记忆（BM25 + confidence 加权）。用任务语义构造 query，不要直接用用户原话。"
                        + "任务开始时、遇到不确定问题时、用户问'之前怎么处理过'时调用。不要每轮都搜，只在需要时调用。",
                createParameters(
                        new Param("query", "string", "检索查询，用任务语义构造（例如'数据库选型决策'而非'用什么数据库'）", true),
                        new Param("limit", "integer", "返回条数，默认 5，最多 20", false),
                        new Param("type", "string", "FACT / PATTERN / DEBUG_INSIGHT / WORKFLOW，可选过滤", false),
                        new Param("scope", "string", "PROJECT / GLOBAL，可选过滤", false)
                ),
                args -> agentMemorySearch(args)
        ));

        tools.put("agent_memory_save", new Tool(
                "agent_memory_save",
                "保存到 Agent 维护的长期记忆。Agent 自主判断，不需要用户确认。"
                        + "confidence < 0.7 不要调用；临时任务/文件名不要保存；"
                        + "不要保存 API key、密码、个人隐私。"
                        + "keywords 必须是专有名词或核心词，3-8 个。",
                createParameters(
                        new Param("fact", "string", "要保存的事实，必须精炼、可跨会话复用", true),
                        new Param("keywords", "string", "提取的关键词，逗号分隔，3-8 个专有名词（例如：SQLite,数据库,存储）", true),
                        new Param("confidence", "number", "0-1 置信度，必须诚实评估，< 0.7 不要调用本工具", true),
                        new Param("type", "string", "FACT / PATTERN / DEBUG_INSIGHT / WORKFLOW", true),
                        new Param("scope", "string", "PROJECT 或 GLOBAL，默认 PROJECT；跨项目通用偏好才用 GLOBAL", false)
                ),
                args -> agentMemorySave(args)
        ));

        tools.put("agent_memory_update", new Tool(
                "agent_memory_update",
                "更新 Agent 维护的记忆。发现旧记忆过时、或要补充新信息时调用。"
                        + "建议先 agent_memory_search 看原内容。",
                createParameters(
                        new Param("id", "string", "要更新的记忆 ID", true),
                        new Param("content", "string", "新内容（可选，不传则保留原内容）", false),
                        new Param("keywords", "string", "新关键词，逗号分隔（可选）", false),
                        new Param("confidence", "number", "新置信度 0-1（可选）", false)
                ),
                args -> agentMemoryUpdate(args)
        ));

        tools.put("agent_memory_delete", new Tool(
                "agent_memory_delete",
                "删除 Agent 维护的记忆。发现旧记忆已过时、错误或不再适用时调用。"
                        + "删除前建议先 agent_memory_search 确认要删除的条目。",
                createParameters(
                        new Param("id", "string", "要删除的记忆 ID", true)
                ),
                args -> agentMemoryDelete(args)
        ));
    }

    /**
     * 注册 ReAct 轻量规划工具 update_plan（对标 Claude Code TodoWrite）。
     *
     * <p>设计要点：
     * <ul>
     *   <li>replace 语义：每次调用传完整任务列表，整体覆盖 store，避免漏状态。</li>
     *   <li>tasks 用 markdown checkbox 字符串编码（换行分隔），因为内置工具的
     *       Map&lt;String,String&gt; 入口不支持 JSON 数组（asText 会拍扁结构）。</li>
     *   <li>状态标记：{@code [ ]} 待办 / {@code [~]} 进行中 / {@code [x]} 已完成。
     *       空字符串或纯空白 = 清空计划。</li>
     *   <li>低危工具：只写 Agent 内存态，不走 HITL 审批；落盘由 Agent 层负责。</li>
     * </ul>
     */
    private void registerPlanTool() {
        tools.put("update_plan", new Tool(
                "update_plan",
                "更新当前 ReAct 会话的任务计划（对标 Claude Code TodoWrite）。"
                        + "遇到多步骤复杂任务时，先用本工具列出步骤，逐步执行并在每步完成后更新状态；"
                        + "简单单步任务不需要调用本工具。"
                        + "每次传入完整任务列表（replace 语义，不是增量）。",
                createParameters(
                        new Param("tasks", "string",
                                "完整任务列表，换行分隔，每行格式 '[状态] 任务描述'。"
                                        + "状态：[ ] 待办 / [~] 进行中 / [x] 已完成。"
                                        + "例：'[ ] 读取 auth 模块\\n[~] 重构 token 校验\\n[x] 补测试'。"
                                        + "空字符串表示清空计划。", true)
                ),
                args -> updatePlan(args)
        ));
    }

    private String updatePlan(Map<String, String> args) {
        if (planStore == null) {
            return "update_plan 失败: PlanStore 未初始化（ReAct 规划存储未注入）";
        }
        String tasks = args.get("tasks");
        if (tasks == null || tasks.isBlank()) {
            planStore.clear();
            return "已清空当前计划。";
        }
        java.util.List<com.bettercli.agent.ReActPlan> parsed = parsePlanTasks(tasks);
        planStore.replace(parsed);
        return "计划已更新。\n" + planStore.formatView();
    }

    /**
     * 注册 peer-to-peer 留言工具 ask_peer（对标 Claude Code agent teams：worker 间直接消息）。
     *
     * <p>设计要点：
     * <ul>
     *   <li>仅 Multi-Agent 模式可用：AgentOrchestrator 派活时注入 {@code sharedState} + {@code currentWorkerName}；
     *       主 ReAct Agent 不注入，故 ask_peer 对 ReAct 不可用（schema 不暴露——见 getToolDefinitions 白名单）。</li>
     *   <li>异步留言：消息存黑板 {@code peerMessages}，对方 worker 下次执行前由 orchestrator 注入 inbox。</li>
     *   <li>不阻塞、不等回复：对标 2026 共识"p2p 难调试"，保持单向留言语义，避免实时对话的死锁/时序问题。</li>
     *   <li>低危：只写黑板内存态，不走 HITL 审批。</li>
     * </ul>
     */
    private void registerPeerTool() {
        tools.put("ask_peer", new Tool(
                "ask_peer",
                "在 Multi-Agent 协作中向另一个 worker 发留言（peer-to-peer，异步）。"
                        + "用于跨步骤协调：例如向负责某模块的同事确认接口、索取已生成的产物摘要。"
                        + "消息存共享黑板，对方下次执行前会看到；不会立即收到回复，不要为此阻塞。",
                createParameters(
                        new Param("to", "string", "目标 worker 名；留空表示广播给所有 worker", false),
                        new Param("message", "string", "留言内容：问题或请求，简明扼要", true)
                ),
                args -> askPeer(args)
        ));
    }

    private String askPeer(Map<String, String> args) {
        if (sharedState == null) {
            return "ask_peer 失败: 共享黑板未初始化（仅 Multi-Agent 模式可用）";
        }
        String from = currentWorkerName;
        if (from == null || from.isBlank()) {
            return "ask_peer 失败: 当前 worker 名未设置";
        }
        String to = args.get("to");
        String message = args.get("message");
        if (message == null || message.isBlank()) {
            return "ask_peer 失败: message 不能为空";
        }
        sharedState.postPeerMessage(from, to, message);
        String target = (to == null || to.isBlank()) ? "所有 worker" : to;
        return "已向 " + target + " 发送留言: " + message;
    }

    /**
     * 解析 markdown checkbox 格式的任务列表为 ReActPlan。
     * 每行格式 '[状态] 内容'，状态标记：[ ] / [~] / [x]（大小写不敏感）。
     * 无标记行视为 pending。空行跳过。id 由 store 自动分配。
     */
    private java.util.List<com.bettercli.agent.ReActPlan> parsePlanTasks(String tasks) {
        java.util.List<com.bettercli.agent.ReActPlan> result = new java.util.ArrayList<>();
        for (String line : tasks.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = PLAN_LINE_PATTERN.matcher(trimmed);
            com.bettercli.agent.ReActPlan.Status status = com.bettercli.agent.ReActPlan.Status.PENDING;
            String content = trimmed;
            if (m.matches()) {
                status = parsePlanStatus(m.group(1));
                content = m.group(2).strip();
            }
            if (content.isEmpty()) {
                continue;
            }
            // id 留空，由 PlanStore.replace 自动分配稳定序号
            result.add(new com.bettercli.agent.ReActPlan(null, content, status, System.currentTimeMillis()));
        }
        return result;
    }

    private static final java.util.regex.Pattern PLAN_LINE_PATTERN =
            java.util.regex.Pattern.compile("^\\[([^\\]]*)\\]\\s*(.*)$");

    private com.bettercli.agent.ReActPlan.Status parsePlanStatus(String marker) {
        if (marker == null) {
            return com.bettercli.agent.ReActPlan.Status.PENDING;
        }
        String s = marker.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "x", "✓", "done", "completed" -> com.bettercli.agent.ReActPlan.Status.COMPLETED;
            case "~", ">", "doing", "in_progress", "in-progress", "wip" -> com.bettercli.agent.ReActPlan.Status.IN_PROGRESS;
            default -> com.bettercli.agent.ReActPlan.Status.PENDING;
        };
    }

    private String agentMemorySearch(Map<String, String> args) {
        if (agentMemoryStore == null) {
            return "agent_memory_search 失败: Agent 记忆存储未初始化";
        }
        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "agent_memory_search 失败: query 不能为空";
        }
        int limit = clamp(parseInt(args.get("limit"), 5), 1, 20);
        AgentMemoryEntry.MemoryType type = parseMemoryType(args.get("type"));
        AgentMemoryEntry.MemoryScope scope = parseMemoryScope(args.get("scope"));
        MemorySearchQuery searchQuery = MemorySearchQuery.builder()
                .query(query.trim())
                .limit(limit)
                .type(type)
                .scope(scope)
                .project(projectPath)
                .build();
        // 记录用户查询到词汇表（用于后续 boost）
        agentMemoryStore.recordUserQuery(query.trim());
        List<MemorySearchResult> results = agentMemoryStore.search(searchQuery);
        if (results.isEmpty()) {
            return "未找到与查询相关的 Agent 记忆: " + query;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 Agent 记忆检索: ").append(query).append("\n");
        sb.append("匹配结果 ").append(results.size()).append(" 条:\n\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i).formatForTool()).append("\n");
        }
        return sb.toString().trim();
    }

    private String agentMemorySave(Map<String, String> args) {
        if (agentMemoryStore == null) {
            return "agent_memory_save 失败: Agent 记忆存储未初始化";
        }
        String fact = args.get("fact");
        if (fact == null || fact.isBlank()) {
            return "agent_memory_save 失败: fact 不能为空";
        }
        String keywordsStr = args.get("keywords");
        if (keywordsStr == null || keywordsStr.isBlank()) {
            return "agent_memory_save 失败: keywords 不能为空";
        }
        double confidence = parseDouble(args.get("confidence"), 0.0);
        if (confidence < 0.7) {
            return "agent_memory_save 失败: confidence " + confidence + " 低于 0.7 门槛，"
                    + "只有高置信度的稳定事实才应保存";
        }
        // 敏感词拦截
        String sensitiveCheck = checkSensitiveContent(fact);
        if (sensitiveCheck != null) {
            return "agent_memory_save 失败: " + sensitiveCheck;
        }
        AgentMemoryEntry.MemoryType type = parseMemoryType(args.get("type"));
        if (type == null) {
            return "agent_memory_save 失败: type 不能为空，必须是 FACT / PATTERN / DEBUG_INSIGHT / WORKFLOW";
        }
        AgentMemoryEntry.MemoryScope scope = parseMemoryScope(args.get("scope"));
        List<String> keywords = parseKeywords(keywordsStr);
        if (keywords.size() < 3 || keywords.size() > 8) {
            return "agent_memory_save 失败: keywords 必须是 3-8 个，当前 " + keywords.size() + " 个";
        }
        // 自动去重检查
        if (agentMemoryStore.findSimilar(fact, keywords, 0.0).isPresent()) {
            return "agent_memory_save 跳过: 检测到与已有记忆高度相似，未保存。可先 agent_memory_search 查看现有条目";
        }
        String id = generateMemoryId();
        AgentMemoryEntry entry = AgentMemoryEntry.builder()
                .id(id)
                .content(fact.trim())
                .keywords(keywords)
                .type(type)
                .scope(scope)
                .project(scope == AgentMemoryEntry.MemoryScope.PROJECT ? projectPath : null)
                .confidence(confidence)
                .source(AgentMemoryEntry.MemorySource.AGENT_TOOL)
                .build();
        try {
            agentMemoryStore.store(entry);
            return "💾 已保存到 Agent 记忆: " + id
                    + "\n内容: " + (fact.length() > 100 ? fact.substring(0, 100) + "..." : fact)
                    + "\n类型: " + type + " / 作用域: " + scope
                    + "\n置信度: " + confidence
                    + "\n关键词: " + String.join(", ", keywords);
        } catch (IllegalStateException e) {
            return "agent_memory_save 失败: " + e.getMessage();
        }
    }

    private String agentMemoryUpdate(Map<String, String> args) {
        if (agentMemoryStore == null) {
            return "agent_memory_update 失败: Agent 记忆存储未初始化";
        }
        String id = args.get("id");
        if (id == null || id.isBlank()) {
            return "agent_memory_update 失败: id 不能为空";
        }
        MemoryEntryPatch.Builder patchBuilder = MemoryEntryPatch.builder();
        String content = args.get("content");
        if (content != null && !content.isBlank()) {
            String sensitiveCheck = checkSensitiveContent(content);
            if (sensitiveCheck != null) {
                return "agent_memory_update 失败: " + sensitiveCheck;
            }
            patchBuilder.content(content.trim());
        }
        String keywordsStr = args.get("keywords");
        if (keywordsStr != null && !keywordsStr.isBlank()) {
            List<String> keywords = parseKeywords(keywordsStr);
            if (keywords.size() < 3 || keywords.size() > 8) {
                return "agent_memory_update 失败: keywords 必须是 3-8 个，当前 " + keywords.size() + " 个";
            }
            patchBuilder.keywords(keywords);
        }
        String confidenceStr = args.get("confidence");
        if (confidenceStr != null && !confidenceStr.isBlank()) {
            patchBuilder.confidence(parseDouble(confidenceStr, 0.5));
        }
        MemoryEntryPatch patch = patchBuilder.build();
        if (patch.isEmpty()) {
            return "agent_memory_update 失败: 至少要提供一个要更新的字段（content / keywords / confidence）";
        }
        boolean updated = agentMemoryStore.update(id, patch);
        if (updated) {
            return "✅ 已更新 Agent 记忆: " + id;
        }
        return "agent_memory_update 失败: 未找到 ID 为 " + id + " 的记忆条目";
    }

    private String agentMemoryDelete(Map<String, String> args) {
        if (agentMemoryStore == null) {
            return "agent_memory_delete 失败: Agent 记忆存储未初始化";
        }
        String id = args.get("id");
        if (id == null || id.isBlank()) {
            return "agent_memory_delete 失败: id 不能为空";
        }
        boolean deleted = agentMemoryStore.delete(id);
        if (deleted) {
            return "🗑️ 已删除 Agent 记忆: " + id;
        }
        return "agent_memory_delete 失败: 未找到 ID 为 " + id + " 的记忆条目";
    }

    private AgentMemoryEntry.MemoryType parseMemoryType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AgentMemoryEntry.MemoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AgentMemoryEntry.MemoryScope parseMemoryScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AgentMemoryEntry.MemoryScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> parseKeywords(String keywordsStr) {
        List<String> keywords = new ArrayList<>();
        for (String kw : keywordsStr.split("[,，]")) {
            String trimmed = kw.trim();
            if (!trimmed.isEmpty()) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String generateMemoryId() {
        return "mem-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 10000);
    }

    /**
     * 敏感词拦截：检测 API key、密码、token 等不应保存到长期记忆的内容。
     * 返回拒绝原因，null 表示通过。
     */
    private static String checkSensitiveContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("api key") || lower.contains("api_key") || lower.contains("apikey")) {
            return "检测到 API key 相关内容，不应保存到长期记忆";
        }
        if (lower.contains("password") || lower.contains("passwd") || lower.contains("密码")) {
            return "检测到密码相关内容，不应保存到长期记忆";
        }
        if (lower.contains("secret") || lower.contains("token") && lower.length() > 20) {
            return "检测到 secret/token 相关内容，不应保存到长期记忆";
        }
        if (lower.contains("bearer ") || lower.contains("authorization:")) {
            return "检测到认证凭据相关内容，不应保存到长期记忆";
        }
        return null;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static String normalizeGlob(String pattern) {
        String normalized = pattern == null ? "**/*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "**/*";
        }
        if (!normalized.contains("/") && !normalized.startsWith("**")) {
            return "**/" + normalized;
        }
        return normalized;
    }

    private static String normalizeFileNameGlob(String pattern) {
        String normalized = pattern == null ? "*" : pattern.replace('\\', '/').trim();
        if (normalized.isEmpty()) {
            return "*";
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static final class SearchFileVisitor extends SimpleFileVisitor<Path> {
        private final Path projectRoot;
        private final java.util.function.Consumer<Path> fileConsumer;

        private SearchFileVisitor(Path projectRoot, java.util.function.Consumer<Path> fileConsumer) {
            this.projectRoot = projectRoot;
            this.fileConsumer = fileConsumer;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(projectRoot) && SEARCH_EXCLUDED_DIRS.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            fileConsumer.accept(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        if (shouldPreferStepSearch() && tools.containsKey(STEP_SEARCH_TOOL)) {
            ObjectNode args = mapper.createObjectNode();
            args.put("query", query.trim());
            putIfStepToolAccepts(STEP_SEARCH_TOOL, args, topK,
                    "top_k", "topK", "max_results", "num_results", "limit", "count");
            ToolOutput output = executeToolOutput(STEP_SEARCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🔍 [StepSearch] " + query.trim() + "\n\n" + output.text().trim();
            }
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
    }

    private void runPostEditLspHook(String displayPath, Path safePath) {
        try {
            if (lspManager != null) {
                lspManager.runPostEditLspHook(displayPath, safePath);
            }
        } catch (Exception ignored) {
            // LSP 诊断是 post-edit 辅助信号，失败不能影响工具主结果。
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }
        if (shouldPreferStepSearch() && tools.containsKey(STEP_FETCH_TOOL)) {
            ObjectNode args = mapper.createObjectNode();
            args.put("url", url.trim());
            putIfStepToolAccepts(STEP_FETCH_TOOL, args, maxChars,
                    "max_chars", "maxChars", "limit", "max_length", "maxLength");
            ToolOutput output = executeToolOutput(STEP_FETCH_TOOL, args.toString());
            if (isUsableMcpOutput(output)) {
                return "🌐 [StepSearch] 抓取: " + url.trim() + "\n\n" + output.text().trim();
            }
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private boolean shouldPreferStepSearch() {
        return "step".equals(currentProvider) && currentModel.startsWith("step-3.7-flash");
    }

    private void putIfStepToolAccepts(String toolName, ObjectNode args, int value, String... names) {
        if (value <= 0 || names == null || names.length == 0) {
            return;
        }
        McpRegisteredTool tool = mcpTools.get(toolName);
        JsonNode properties = tool == null ? null : tool.descriptor().inputSchema().path("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (String name : names) {
            if (properties.has(name)) {
                args.put(name, value);
                return;
            }
        }
    }

    private boolean isUsableMcpOutput(ToolOutput output) {
        if (output == null || output.text() == null || output.text().isBlank()) {
            return false;
        }
        String text = output.text().trim();
        return !text.startsWith("[HITL]")
                && !text.startsWith("🛡️")
                && !text.startsWith("工具执行失败")
                && !text.startsWith("未知工具")
                && !text.startsWith("MCP 工具返回错误");
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }

    /**
     * 创建参数定义
     */
    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     */
    public List<com.bettercli.llm.LlmClient.Tool> getToolDefinitions() {
        return getToolDefinitions(null);
    }

    /**
     * 按白名单获取工具定义（用于LLM）。
     *
     * @param whitelist 允许的工具名集合；{@code null} 表示不限制（返回全部，含 MCP 动态工具）。
     *                  非空时只返回白名单内的内置工具，MCP 工具（mcp__*）一律不暴露——
     *                  非 WORKER 角色不应直接调用 MCP，避免越权。
     */
    public List<com.bettercli.llm.LlmClient.Tool> getToolDefinitions(Set<String> whitelist) {
        return tools.values().stream()
                .filter(t -> whitelist == null || whitelist.contains(t.name()))
                // ask_peer 仅 Multi-Agent 模式可用：sharedState 未注入时（主 ReAct / 未 run 的 orchestrator）
                // 不暴露给 LLM，避免调用即失败的工具污染 schema。
                .filter(t -> !("ask_peer".equals(t.name()) && sharedState == null))
                .map(t -> new com.bettercli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 注册一个 MCP 工具到 ToolRegistry。
     *
     * @param descriptor 工具描述（含 namespacedName 如 mcp__filesystem__read_file）
     * @param invoker    工具执行器：输入 JSON 参数字符串，输出给 LLM 看的字符串结果。
     *                   typically lambda 在内部调用 McpClient.callTool 并处理异常 → 字符串。
     */
    public synchronized void registerMcpTool(McpToolDescriptor descriptor, Function<String, String> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        registerMcpToolOutput(descriptor, args -> ToolOutput.text(invoker.apply(args)));
    }

    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        String toolName = descriptor.namespacedName();
        McpRegisteredTool registered = new McpRegisteredTool(descriptor, invoker);
        mcpTools.put(toolName, registered);
        tools.put(toolName, new Tool(
                toolName,
                mcpDescription(descriptor),
                descriptor.inputSchema(),
                args -> "MCP 工具不应通过 Map<String,String> 入口执行"
        ));
    }

    public synchronized void unregisterMcpTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        mcpTools.remove(toolName);
        tools.remove(toolName);
    }

    public synchronized void replaceMcpToolsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                      Function<McpToolDescriptor, Function<String, String>> invokerFactory) {
        replaceMcpToolOutputsForServer(serverName, newTools,
                descriptor -> args -> ToolOutput.text(invokerFactory.apply(descriptor).apply(args)));
    }

    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
                                                            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(newTools, "newTools");
        Objects.requireNonNull(invokerFactory, "invokerFactory");
        String prefix = "mcp__" + serverName + "__";
        List<String> existing = mcpTools.keySet().stream()
                .filter(name -> name.startsWith(prefix))
                .toList();
        for (String toolName : existing) {
            mcpTools.remove(toolName);
            tools.remove(toolName);
        }
        for (McpToolDescriptor descriptor : newTools) {
            registerMcpToolOutput(descriptor, invokerFactory.apply(descriptor));
        }
    }

    /**
     * 执行工具调用
     *
     * 危险工具（write_file / execute_command / create_project）会写一行审计：
     * - 策略拦截（PathGuard / CommandGuard / 文件大小上限）→ deny
     * - 普通异常 → error
     * - 其他情况 → allow（仅表示工具调用真的发生过，工具内部的业务错误仍以返回字符串呈现给 LLM）
     */
    public String executeTool(String name, String argumentsJson) {
        return doExecuteTool(name, argumentsJson).text();
    }

    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        if (isLegacyExecuteToolOverride()) {
            return ToolOutput.text(executeTool(name, argumentsJson));
        }
        return doExecuteTool(name, argumentsJson);
    }

    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        if (CancellationContext.isCancelled()) {
            return ToolOutput.text("用户取消了此次工具调用");
        }
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolOutput.text("未知工具: " + name);
        }

        boolean shouldAudit = shouldAudit(name);
        long start = System.nanoTime();
        BrowserAuditMetadata auditMetadata = null;

        try {
            McpRegisteredTool mcpTool = mcpTools.get(name);
            if (mcpTool != null) {
                BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, false);
                auditMetadata = browserCheck.metadata();
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
                ToolOutput output = mcpTool.invoker().apply(argumentsJson);
                if (output == null) {
                    output = ToolOutput.text("");
                }
                if (browserGuard != null) {
                    browserGuard.applyAfterExecution(name, argumentsJson, output.text());
                }
                if (shouldAudit) {
                    auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
                }
                return output;
            }

            JsonNode args = mapper.readTree(argumentsJson);
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            String result = tool.executor().execute(argMap);
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.allow(name, argumentsJson, elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text(result);
        } catch (PolicyException e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.denyByPolicy(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text("🛡️ 策略拒绝: " + e.getMessage());
        } catch (Exception e) {
            if (shouldAudit) {
                auditLog.record(AuditLog.AuditEntry.error(
                        name, argumentsJson, e.getMessage(), elapsedMillis(start), auditMetadata));
            }
            return ToolOutput.text("工具执行失败: " + e.getMessage());
        }
    }

    private boolean isLegacyExecuteToolOverride() {
        try {
            return getClass()
                    .getMethod("executeTool", String.class, String.class)
                    .getDeclaringClass() != ToolRegistry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    protected BrowserCheckResult checkBrowserTool(String name, String argumentsJson, boolean previewOnly) {
        if (browserGuard == null || !BrowserGuard.isChromeTool(name)) {
            return BrowserCheckResult.allow(null);
        }
        return browserGuard.check(name, argumentsJson, !previewOnly);
    }

    public AuditLog getAuditLog() {
        return auditLog;
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        return executeTools(invocations, null);
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用，可按白名单拦截越权调用。
     *
     * @param invocations 工具调用列表
     * @param whitelist   允许的工具名集合；{@code null} 表示不限制。非空时，不在白名单内的调用
     *                    （含 mcp__*）直接返回拒绝结果，不进入执行路径。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations, Set<String> whitelist) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "用户取消了此次工具调用"))
                    .toList();
        }
        // 白名单预检：把越权调用直接转成失败结果，避免占用线程池或触发副作用工具
        if (whitelist != null) {
            List<ToolExecutionResult> results = new ArrayList<>();
            boolean anyBlocked = false;
            for (ToolInvocation invocation : invocations) {
                String denyReason = whitelistDenyReason(invocation.name(), whitelist);
                if (denyReason != null) {
                    anyBlocked = true;
                    results.add(ToolExecutionResult.failed(invocation, denyReason));
                } else {
                    results.add(null);
                }
            }
            if (anyBlocked) {
                // 仅对未越权的调用继续执行，越权的已落盘为 failed
                List<ToolInvocation> allowed = new ArrayList<>();
                List<Integer> allowedIndexes = new ArrayList<>();
                for (int i = 0; i < invocations.size(); i++) {
                    if (results.get(i) == null) {
                        allowed.add(invocations.get(i));
                        allowedIndexes.add(i);
                    }
                }
                List<ToolExecutionResult> allowedResults = executeToolsUnrestricted(allowed);
                for (int j = 0; j < allowedResults.size(); j++) {
                    results.set(allowedIndexes.get(j), allowedResults.get(j));
                }
                return results;
            }
        }
        return executeToolsUnrestricted(invocations);
    }

    private List<ToolExecutionResult> executeToolsUnrestricted(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "bettercli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        if (CancellationContext.isCancelled()) {
                            return ToolExecutionResult.failed(invocation, "用户取消了此次工具调用");
                        }
                        long startedAt = System.nanoTime();
                        ToolOutput output = executeToolOutput(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 返回非 null 表示该工具被白名单拒绝，附带拒绝原因；返回 null 表示放行。
     */
    private static String whitelistDenyReason(String toolName, Set<String> whitelist) {
        if (whitelist == null) {
            return null;
        }
        if (toolName == null || !whitelist.contains(toolName)) {
            return "🛡️ 角色权限拒绝: " + toolName + " 不在当前角色工具白名单内";
        }
        return null;
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private static boolean shouldAudit(String name) {
        return AUDIT_TOOLS.contains(name) || (name != null && name.startsWith("mcp__"));
    }

    private static String mcpDescription(McpToolDescriptor descriptor) {
        String base = descriptor.description() == null || descriptor.description().isBlank()
                ? "MCP server 提供的外部工具"
                : descriptor.description();
        return base + " (MCP server: " + descriptor.serverName() + ", tool: " + descriptor.name() + ")";
    }

    private String executeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            // 抛 PolicyException 让外层 executeTool 统一写 audit 并格式化拒绝消息，
            // 命令围栏与路径围栏的拒绝路径走同一个出口。
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "bettercli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", normalized)
                    : new ProcessBuilder("bash", "-c", normalized);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return "用户取消了此次工具调用";
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    private record McpRegisteredTool(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {}

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis, boolean timedOut,
                                      List<com.bettercli.llm.LlmClient.ContentPart> imageParts) {
        private static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output, long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    output == null ? "" : output.text(),
                    elapsedMillis,
                    false,
                    output == null ? List.of() : output.imageParts());
        }

        private static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
            return completed(invocation, ToolOutput.text(result), elapsedMillis);
        }

        private static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return completed(invocation, "工具执行失败: " + message, 0);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    timeoutSeconds * 1000,
                    true,
                    List.of()
            );
        }

        public boolean hasImageParts() {
            return imageParts != null && !imageParts.isEmpty();
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }
}
