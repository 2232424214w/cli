package com.bettercli.memory;

import com.bettercli.llm.LlmClient;
import com.bettercli.context.ContextProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理短期记忆、长期记忆、上下文压缩和检索，
 * 为 Agent 提供简洁的记忆存取接口。
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final Pattern KEYWORD_TOKEN = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z][A-Za-z0-9._-]{1,}");

    private final ConversationMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final MemoryRetriever retriever;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject;
    /** 对标 1024 agent_memory：/save 与 save_memory 的主写入路径（可空，测试可不注入）。 */
    private AgentMemoryStore agentMemoryStore;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient      LLM 客户端（用于压缩时的摘要生成）
     * @param shortTermBudget 短期记忆 token 预算
     * @param contextWindow  模型上下文窗口大小
     */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.shortTermMemory = new ConversationMemory(contextProfile.shortTermMemoryBudget());
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.compressor = new ContextCompressor(llmClient);
        this.retriever = new MemoryRetriever(shortTermMemory, this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
        maybeEnableSemanticRetrieval();
    }

    /**
     * 长期记忆语义检索：默认开启（embedding 失败自动回退关键词）。
     * 可用 {@code bettercli.memory.semantic.enabled=false} / {@code BETTERCLI_MEMORY_SEMANTIC=false} 关闭。
     */
    private void maybeEnableSemanticRetrieval() {
        String prop = System.getProperty("bettercli.memory.semantic.enabled");
        String env = System.getenv("BETTERCLI_MEMORY_SEMANTIC");
        String raw = prop != null ? prop : env;
        boolean enabled = raw == null || !raw.equalsIgnoreCase("false");
        if (!enabled) {
            return;
        }
        try {
            MemoryVectorIndex index = new MemoryVectorIndex(new com.bettercli.rag.EmbeddingClient());
            retriever.setVectorIndex(index);
            log.info("长期记忆语义检索已启用（embedding 失败时回退关键词）");
        } catch (Exception e) {
            log.warn("无法初始化记忆语义索引，使用关键词检索: {}", e.toString());
        }
    }

    /** 测试 / 注入用：自定义 embedder（如 stub） */
    public void setMemoryVectorIndex(MemoryVectorIndex index) {
        retriever.setVectorIndex(index);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.compressor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.shortTermMemory.setMaxTokens(contextProfile.shortTermMemoryBudget());
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    /**
     * 添加用户消息到短期记忆
     */
    public void addUserMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "user"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 添加助手回复到短期记忆
     */
    public void addAssistantMessage(String content) {
        MemoryEntry entry = new MemoryEntry(
                "assistant-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.CONVERSATION,
                Map.of("source", "assistant"),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    // 工具结果在记忆中的最大长度（完整结果已在任务消息历史里，记忆只需保留摘要）
    private static final int MAX_TOOL_RESULT_CHARS = 500;

    /**
     * 添加工具执行结果到短期记忆（截断过长结果，避免快速撑满预算）
     */
    public void addToolResult(String toolName, String result) {
        String truncated = result.length() > MAX_TOOL_RESULT_CHARS
                ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "...(已截断)"
                : result;
        String content = "[" + toolName + "] " + truncated;
        MemoryEntry entry = new MemoryEntry(
                "tool-" + UUID.randomUUID().toString().substring(0, 8),
                content,
                MemoryEntry.MemoryType.TOOL_RESULT,
                Map.of("source", "tool", "toolName", toolName),
                MemoryEntry.estimateTokens(content)
        );
        shortTermMemory.store(entry);
        compressIfNeeded();
    }

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        storeFact(fact, "project");
    }

    public void storeFact(String fact, String scope) {
        storeFact(fact, scope, "fact");
    }

    public void storeFact(String fact, String scope, String source) {
        String normalizedScope = normalizeScope(scope);
        String src = source == null || source.isBlank() ? "fact" : source;
        String id = "fact-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> metadata = "global".equals(normalizedScope)
                ? Map.of("source", src, "scope", "global")
                : Map.of("source", src, "scope", "project", "project", currentProject);
        MemoryEntry entry = new MemoryEntry(
                id,
                fact,
                MemoryEntry.MemoryType.FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        // 主路径：agent_memory（对标 1024）
        storeFactToAgentMemory(id, fact, normalizedScope, src);
        // JSON 双写默认关闭；无 agent store 时仍写 JSON 兜底；显式 dual_write 可开
        if (agentMemoryStore == null || isJsonDualWriteEnabled()) {
            longTermMemory.store(entry);
            MemoryVectorIndex index = retriever.getVectorIndex();
            if (index != null) {
                index.invalidate(entry.getId());
            }
        }
    }

    /**
     * 是否双写旧 JSON LongTermMemory。默认 false（P2-3）。
     * {@code bettercli.memory.json_dual_write.enabled=true} /
     * {@code BETTERCLI_MEMORY_JSON_DUAL_WRITE=true}
     */
    public static boolean isJsonDualWriteEnabled() {
        String prop = System.getProperty("bettercli.memory.json_dual_write.enabled");
        String env = System.getenv("BETTERCLI_MEMORY_JSON_DUAL_WRITE");
        String raw = prop != null ? prop : env;
        return raw != null && raw.equalsIgnoreCase("true");
    }

    public void setAgentMemoryStore(AgentMemoryStore agentMemoryStore) {
        this.agentMemoryStore = agentMemoryStore;
    }

    public AgentMemoryStore getAgentMemoryStore() {
        return agentMemoryStore;
    }

    /**
     * Legacy 每轮被动预取（MemoryRetriever 注入 system prompt）。
     * 默认关闭，对齐 1024「不做自动预取」；开启：
     * {@code bettercli.memory.legacy_prefetch.enabled=true} /
     * {@code BETTERCLI_MEMORY_LEGACY_PREFETCH=true}。
     */
    public static boolean isLegacyPrefetchEnabled() {
        String prop = System.getProperty("bettercli.memory.legacy_prefetch.enabled");
        String env = System.getenv("BETTERCLI_MEMORY_LEGACY_PREFETCH");
        String raw = prop != null ? prop : env;
        return raw != null && raw.equalsIgnoreCase("true");
    }

    private void storeFactToAgentMemory(String id, String fact, String normalizedScope, String src) {
        if (agentMemoryStore == null || fact == null || fact.isBlank()) {
            return;
        }
        try {
            AgentMemoryEntry.MemorySource memSource =
                    "fact_extractor".equals(src)
                            ? AgentMemoryEntry.MemorySource.AGENT_TOOL
                            : AgentMemoryEntry.MemorySource.EXPLICIT_HINT;
            AgentMemoryEntry.MemoryScope memScope =
                    "global".equals(normalizedScope)
                            ? AgentMemoryEntry.MemoryScope.GLOBAL
                            : AgentMemoryEntry.MemoryScope.PROJECT;
            List<String> keywords = extractKeywords(fact);
            if (keywords.size() < 3) {
                // agent_memory_save 要求 3-8 个；不足时用占位补齐，保证用户显式 /save 可落库
                List<String> padded = new ArrayList<>(keywords);
                String[] fillers = {"preference", "stable-fact", "user-save", "project-memory"};
                for (String f : fillers) {
                    if (padded.size() >= 3) {
                        break;
                    }
                    if (!padded.contains(f)) {
                        padded.add(f);
                    }
                }
                keywords = padded;
            }
            AgentMemoryEntry agentEntry = AgentMemoryEntry.builder()
                    .id(id)
                    .content(fact.trim())
                    .keywords(keywords.size() > 8 ? keywords.subList(0, 8) : keywords)
                    .type(AgentMemoryEntry.MemoryType.FACT)
                    .scope(memScope)
                    .project("global".equals(normalizedScope) ? null : currentProject)
                    .confidence(1.0)
                    .source(memSource)
                    .status(AgentMemoryEntry.MemoryStatus.ACTIVE)
                    .build();
            agentMemoryStore.store(agentEntry);
        } catch (Exception e) {
            log.warn("写入 agent_memory 失败: {}", e.toString());
        }
    }

    static List<String> extractKeywords(String fact) {
        List<String> out = new ArrayList<>();
        if (fact == null || fact.isBlank()) {
            return out;
        }
        Matcher m = KEYWORD_TOKEN.matcher(fact.toLowerCase(Locale.ROOT));
        while (m.find() && out.size() < 8) {
            String t = m.group();
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 压缩前可选自动提取稳定事实（默认关闭）。
     * 开启：{@code bettercli.memory.auto_extract.enabled=true} / {@code BETTERCLI_MEMORY_AUTO_EXTRACT=true}
     */
    public int maybeExtractFacts() {
        if (!isAutoExtractEnabled()) {
            return 0;
        }
        List<MemoryEntry> snapshot = shortTermMemory.getAll();
        if (snapshot.size() < 4) {
            return 0;
        }
        try {
            List<String> facts = compressor.extractFactCandidates(snapshot);
            int saved = 0;
            for (String fact : facts) {
                storeFact(fact, "project", "fact_extractor");
                saved++;
            }
            if (saved > 0) {
                log.info("自动提取并写入 {} 条长期事实（source=fact_extractor）", saved);
            }
            return saved;
        } catch (Exception e) {
            log.warn("自动事实提取失败: {}", e.toString());
            return 0;
        }
    }

    static boolean isAutoExtractEnabled() {
        String prop = System.getProperty("bettercli.memory.auto_extract.enabled");
        String env = System.getenv("BETTERCLI_MEMORY_AUTO_EXTRACT");
        String raw = prop != null ? prop : env;
        return raw != null && raw.equalsIgnoreCase("true");
    }

    /**
     * 检索与查询最相关的记忆
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        boolean deleted = longTermMemory.delete(id);
        MemoryVectorIndex index = retriever.getVectorIndex();
        if (deleted && index != null) {
            index.invalidate(id);
        }
        return deleted;
    }

    /**
     * 构建用于 LLM 的记忆上下文（仅 Legacy 预取开启时调用）。
     * P2-4：优先 AgentMemoryStore BM25；否则回退 {@link MemoryRetriever}。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        if (agentMemoryStore != null) {
            try {
                List<MemorySearchResult> hits = agentMemoryStore.search(MemorySearchQuery.builder()
                        .query(query == null ? "" : query)
                        .limit(8)
                        .project(currentProject)
                        .build());
                if (!hits.isEmpty()) {
                    StringBuilder sb = new StringBuilder("## 相关长期记忆（agent_memory BM25）\n");
                    int budget = Math.max(200, maxTokens * 3);
                    for (MemorySearchResult hit : hits) {
                        String line = "- " + hit.entry().getContent() + "\n";
                        if (sb.length() + line.length() > budget) {
                            break;
                        }
                        sb.append(line);
                    }
                    return sb.toString().trim();
                }
            } catch (Exception e) {
                log.warn("agent_memory 预取失败，回退 MemoryRetriever: {}", e.toString());
            }
        }
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /**
     * 检查并触发压缩（由 Agent 在 LLM 调用前主动调用）
     *
     * @return 是否执行了压缩
     */
    public boolean compressIfNeeded() {
        // 压缩永远可触发，模式概念已删除。触发条件仅看占用率是否到达 ContextProfile 配置的自动压缩阈值。
        if (!tokenBudget.needsCompression(shortTermMemory, contextProfile.compressionTriggerRatio())) {
            return false;
        }
        int beforeTokens = shortTermMemory.getTokenCount();
        log.info("上下文占用达到压缩阈值（{}%），触发短期记忆压缩",
                (int) (contextProfile.compressionTriggerRatio() * 100));
        maybeExtractFacts();
        String summary = compressor.compress(shortTermMemory);
        if (summary != null) {
            int afterTokens = shortTermMemory.getTokenCount();
            String preview = summary.substring(0, Math.min(100, summary.length()));
            log.info("短期记忆压缩完成: {} -> {} tokens, summaryPreview={}", beforeTokens, afterTokens, preview);
        }
        return summary != null;
    }

    /**
     * 清空短期记忆（保留长期记忆）
     */
    public void clearShortTerm() {
        shortTermMemory.clear();
    }

    /**
     * 清空长期记忆
     */
    public void clearLongTerm() {
        longTermMemory.clear();
        MemoryVectorIndex index = retriever.getVectorIndex();
        if (index != null) {
            index.clear();
        }
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                shortTermMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    // Getter
    public ConversationMemory getShortTermMemory() { return shortTermMemory; }
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }

    public String getCurrentProject() { return currentProject; }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase();
        return "global".equals(normalized) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
