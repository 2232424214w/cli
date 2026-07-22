package com.bettercli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * SQLite FTS5 实现的 Agent 长期记忆存储（对标美团 1024 Agent agent_memory 表）。
 *
 * 设计参考：docs/memory-system-design.md §3.2 / §4.1 / §5
 *
 * 特性：
 * - SQLite FTS5 BM25 全文检索（content + keywords 拼接索引）
 * - confidence 加权打分（final_score = -bm25() * (0.5 + confidence)）
 * - user_vocabulary boost（用户提过的关键词加权）
 * - 容量护栏（默认 1000 条上限）
 * - 自动去重（BM25 相似度阈值）
 * - TTL 清理（pending 超时 / expired 状态）
 * - 从 long_term_memory.json 迁移（启动时自动，不删原文件）
 */
public class SqliteAgentMemoryStore implements AgentMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteAgentMemoryStore.class);

    private static final String STORAGE_DIR_PROPERTY = "bettercli.memory.dir";
    private static final String STORAGE_DIR_ENV = "BETTERCLI_MEMORY_DIR";
    private static final String DB_FILE = "agent_memory.db";
    private static final int DEFAULT_MAX_ENTRIES = 1000;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.85;
    private static final double CONFIDENCE_WEIGHT_BASE = 0.5;  // final = -bm25 * (0.5 + confidence)

    private final Connection connection;
    private final String projectPath;
    private final int maxEntries;
    private final double similarityThreshold;
    private final Map<String, Integer> vocabulary = new ConcurrentHashMap<>();

    public SqliteAgentMemoryStore(String projectPath) throws SQLException {
        this(projectPath, resolveMemoryDir(), DEFAULT_MAX_ENTRIES, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public SqliteAgentMemoryStore(String projectPath, java.io.File memoryDir,
                                  int maxEntries, double similarityThreshold) throws SQLException {
        this.projectPath = projectPath;
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
        this.similarityThreshold = similarityThreshold > 0 ? similarityThreshold : DEFAULT_SIMILARITY_THRESHOLD;
        if (!memoryDir.exists() && !memoryDir.mkdirs()) {
            throw new SQLException("无法创建记忆目录: " + memoryDir);
        }
        String dbPath = new java.io.File(memoryDir, DB_FILE).getAbsolutePath();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        loadVocabulary();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS agent_memory_entries (
                        id TEXT PRIMARY KEY,
                        content TEXT NOT NULL,
                        keywords_json TEXT NOT NULL,
                        type TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        project TEXT,
                        confidence REAL NOT NULL,
                        source TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'active',
                        pending_expires_at TEXT,
                        token_count INTEGER NOT NULL,
                        access_count INTEGER DEFAULT 0,
                        last_accessed_at TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_am_scope_project ON agent_memory_entries(scope, project)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_am_type ON agent_memory_entries(type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_am_confidence ON agent_memory_entries(confidence DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_am_status ON agent_memory_entries(status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_am_last_accessed ON agent_memory_entries(last_accessed_at)");

            stmt.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS agent_memory_fts USING fts5(
                        id UNINDEXED,
                        content,
                        keywords,
                        tokenize = 'unicode61'
                    )
                    """);

            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS agent_memory_ai AFTER INSERT ON agent_memory_entries BEGIN
                        INSERT INTO agent_memory_fts(id, content, keywords)
                        VALUES (new.id, new.content, new.keywords_json);
                    END
                    """);
            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS agent_memory_ad AFTER DELETE ON agent_memory_entries BEGIN
                        DELETE FROM agent_memory_fts WHERE id = old.id;
                    END
                    """);
            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS agent_memory_au AFTER UPDATE ON agent_memory_entries BEGIN
                        DELETE FROM agent_memory_fts WHERE id = old.id;
                        INSERT INTO agent_memory_fts(id, content, keywords)
                        VALUES (new.id, new.content, new.keywords_json);
                    END
                    """);

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS user_vocabulary (
                        term TEXT PRIMARY KEY,
                        frequency INTEGER DEFAULT 1,
                        first_seen_at TEXT,
                        last_seen_at TEXT
                    )
                    """);
        }
    }

    private void loadVocabulary() {
        vocabulary.clear();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT term, frequency FROM user_vocabulary")) {
            while (rs.next()) {
                vocabulary.put(rs.getString("term"), rs.getInt("frequency"));
            }
        } catch (SQLException e) {
            log.warn("加载词汇表失败: {}", e.getMessage());
        }
    }

    private static java.io.File resolveMemoryDir() {
        String configured = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(STORAGE_DIR_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return new java.io.File(configured);
        }
        return new java.io.File(new java.io.File(System.getProperty("user.home"), ".bettercli"), "memory");
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("关闭 Agent 记忆数据库失败: {}", e.getMessage());
        }
    }

    // ==================== CRUD ====================

    @Override
    public void store(AgentMemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.getId() == null || entry.getId().isBlank()) {
            throw new IllegalArgumentException("entry.id 不能为空");
        }
        if (size() >= maxEntries) {
            throw new IllegalStateException("记忆容量已达上限 " + maxEntries + "，请先清理或调整 bettercli.memory.max_entries");
        }
        try {
            String sql = """
                    INSERT OR REPLACE INTO agent_memory_entries
                    (id, content, keywords_json, type, scope, project, confidence, source, status,
                     pending_expires_at, token_count, access_count, last_accessed_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entry.getId());
                ps.setString(2, entry.getContent());
                ps.setString(3, entry.keywordsJson());
                ps.setString(4, entry.getType().name());
                ps.setString(5, entry.getScope().name());
                ps.setString(6, entry.getProject());
                ps.setDouble(7, entry.getConfidence());
                ps.setString(8, entry.getSource().name());
                ps.setString(9, entry.getStatus().name());
                ps.setString(10, entry.getPendingExpiresAt() == null ? null : entry.getPendingExpiresAt().toString());
                ps.setInt(11, entry.getTokenCount());
                ps.setInt(12, entry.getAccessCount());
                ps.setString(13, entry.getLastAccessedAt() == null ? null : entry.getLastAccessedAt().toString());
                ps.setString(14, entry.getCreatedAt().toString());
                ps.setString(15, entry.getUpdatedAt().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存 Agent 记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<AgentMemoryEntry> retrieve(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM agent_memory_entries WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(resultSetToEntry(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("检索 Agent 记忆失败 id={}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean update(String id, MemoryEntryPatch patch) {
        if (id == null || id.isBlank() || patch == null || patch.isEmpty()) {
            return false;
        }
        Optional<AgentMemoryEntry> existing = retrieve(id);
        if (existing.isEmpty()) {
            return false;
        }
        AgentMemoryEntry old = existing.get();
        AgentMemoryEntry.Builder builder = AgentMemoryEntry.builder()
                .id(old.getId())
                .content(patch.getContent() != null ? patch.getContent() : old.getContent())
                .keywords(patch.getKeywords() != null ? patch.getKeywords() : old.getKeywords())
                .type(patch.getType() != null ? patch.getType() : old.getType())
                .scope(patch.getScope() != null ? patch.getScope() : old.getScope())
                .project(old.getProject())
                .confidence(patch.getConfidence() != null ? patch.getConfidence() : old.getConfidence())
                .source(old.getSource())
                .status(patch.getStatus() != null ? patch.getStatus() : old.getStatus())
                .pendingExpiresAt(old.getPendingExpiresAt())
                .tokenCount(old.getTokenCount())
                .accessCount(old.getAccessCount())
                .lastAccessedAt(old.getLastAccessedAt())
                .createdAt(old.getCreatedAt())
                .updatedAt(Instant.now());
        try {
            store(builder.build());
            return true;
        } catch (Exception e) {
            log.warn("更新 Agent 记忆失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM agent_memory_entries WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("删除 Agent 记忆失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public void clear() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM agent_memory_entries");
            stmt.execute("DELETE FROM agent_memory_fts");
        } catch (SQLException e) {
            throw new RuntimeException("清空 Agent 记忆失败: " + e.getMessage(), e);
        }
    }

    // ==================== 检索 ====================

    @Override
    public List<MemorySearchResult> search(MemorySearchQuery query) {
        if (query == null || query.getQuery() == null || query.getQuery().isBlank()) {
            return List.of();
        }
        String ftsQuery = buildFtsQuery(query.getQuery());
        if (ftsQuery.isBlank()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.content, e.keywords_json, e.type, e.scope, e.project,
                       e.confidence, e.source, e.status, e.pending_expires_at, e.token_count,
                       e.access_count, e.last_accessed_at, e.created_at, e.updated_at,
                       bm25(agent_memory_fts) AS bm25_score
                FROM agent_memory_fts f
                JOIN agent_memory_entries e ON e.id = f.id
                WHERE agent_memory_fts MATCH ?
                """);
        List<String> conditions = new ArrayList<>();
        if (!query.isIncludePending()) {
            conditions.add("e.status = 'ACTIVE'");
        }
        if (query.getType() != null) {
            conditions.add("e.type = '" + query.getType().name() + "'");
        }
        // scope + project 组合过滤：
        // - scope + project 都指定：(scope=PROJECT AND project=?) OR scope=GLOBAL
        // - 只 scope 指定：scope=?
        // - 只 project 指定：project=? OR scope=GLOBAL
        if (query.getScope() != null && query.getProject() != null && !query.getProject().isBlank()) {
            if (query.getScope() == AgentMemoryEntry.MemoryScope.PROJECT) {
                conditions.add("((e.scope = 'PROJECT' AND e.project = '"
                        + escapeSql(query.getProject()) + "') OR e.scope = 'GLOBAL')");
            } else {
                conditions.add("e.scope = '" + query.getScope().name() + "'");
            }
        } else if (query.getScope() != null) {
            conditions.add("e.scope = '" + query.getScope().name() + "'");
        } else if (query.getProject() != null && !query.getProject().isBlank()) {
            conditions.add("(e.project = '" + escapeSql(query.getProject()) + "' OR e.scope = 'GLOBAL')");
        }
        if (query.getMinConfidence() != null) {
            conditions.add("e.confidence >= " + query.getMinConfidence());
        }
        if (query.getCreatedAfter() != null) {
            conditions.add("e.created_at >= '" + query.getCreatedAfter() + "'");
        }
        if (query.getCreatedBefore() != null) {
            conditions.add("e.created_at <= '" + query.getCreatedBefore() + "'");
        }
        if (!conditions.isEmpty()) {
            sql.append(" AND ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY bm25_score ASC LIMIT ?");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            ps.setString(1, ftsQuery);
            ps.setInt(2, query.getLimit());
            List<MemorySearchResult> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AgentMemoryEntry entry = resultSetToEntry(rs);
                    double bm25 = rs.getDouble("bm25_score");
                    double confidenceWeight = CONFIDENCE_WEIGHT_BASE + entry.getConfidence();
                    double vocabBoost = computeVocabBoost(entry.getKeywords());
                    double finalScore = -bm25 * confidenceWeight * vocabBoost;
                    results.add(new MemorySearchResult(entry, -bm25, confidenceWeight * vocabBoost, finalScore));
                }
            }
            results.sort(Comparator.comparingDouble(MemorySearchResult::finalScore).reversed());
            updateAccessCounts(results);
            return results;
        } catch (SQLException e) {
            log.warn("BM25 检索失败 query={}: {}", query.getQuery(), e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<AgentMemoryEntry> list(MemoryListQuery query) {
        if (query == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM agent_memory_entries WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (query.getType() != null) {
            sql.append(" AND type = ?");
            params.add(query.getType().name());
        }
        if (query.getScope() != null) {
            sql.append(" AND scope = ?");
            params.add(query.getScope().name());
        }
        if (query.getProject() != null && !query.getProject().isBlank()) {
            sql.append(" AND (project = ? OR scope = 'GLOBAL')");
            params.add(query.getProject());
        }
        if (query.getStatus() != null) {
            sql.append(" AND status = ?");
            params.add(query.getStatus().name());
        }
        String orderBy = switch (query.getOrderBy()) {
            case "updated_at" -> "updated_at";
            case "confidence" -> "confidence DESC";
            case "access_count" -> "access_count DESC";
            default -> "created_at DESC";
        };
        sql.append(" ORDER BY ").append(orderBy).append(" LIMIT ? OFFSET ?");
        params.add(query.getLimit());
        params.add(query.getOffset());
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            List<AgentMemoryEntry> results = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(resultSetToEntry(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            log.warn("list 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 统计 ====================

    @Override
    public int size() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM agent_memory_entries")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("统计 size 失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public MemoryStats stats() {
        try (Statement stmt = connection.createStatement()) {
            int total = 0, active = 0, pending = 0, expired = 0;
            int projectScoped = 0, globalScoped = 0;
            int totalTokens = 0, totalAccess = 0;
            double confidenceSum = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*), " +
                            "SUM(CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END), " +
                            "SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END), " +
                            "SUM(CASE WHEN status='EXPIRED' THEN 1 ELSE 0 END), " +
                            "SUM(CASE WHEN scope='PROJECT' THEN 1 ELSE 0 END), " +
                            "SUM(CASE WHEN scope='GLOBAL' THEN 1 ELSE 0 END), " +
                            "COALESCE(SUM(token_count), 0), " +
                            "COALESCE(SUM(access_count), 0), " +
                            "COALESCE(SUM(confidence), 0) FROM agent_memory_entries")) {
                if (rs.next()) {
                    total = rs.getInt(1);
                    active = rs.getInt(2);
                    pending = rs.getInt(3);
                    expired = rs.getInt(4);
                    projectScoped = rs.getInt(5);
                    globalScoped = rs.getInt(6);
                    totalTokens = rs.getInt(7);
                    totalAccess = rs.getInt(8);
                    confidenceSum = rs.getDouble(9);
                }
            }
            double avgConf = total > 0 ? confidenceSum / total : 0.0;
            return new MemoryStats(total, active, pending, expired,
                    projectScoped, globalScoped, totalTokens, totalAccess, avgConf);
        } catch (SQLException e) {
            log.warn("stats 失败: {}", e.getMessage());
            return MemoryStats.empty();
        }
    }

    // ==================== 词汇表 ====================

    @Override
    public void recordUserQuery(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        Set<String> terms = MemoryQueryTokenizer.tokenize(query);
        Instant now = Instant.now();
        for (String term : terms) {
            vocabulary.merge(term, 1, Integer::sum);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO user_vocabulary (term, frequency, first_seen_at, last_seen_at) " +
                            "VALUES (?, 1, ?, ?) " +
                            "ON CONFLICT(term) DO UPDATE SET " +
                            "frequency = frequency + 1, last_seen_at = ?")) {
                ps.setString(1, term);
                ps.setString(2, now.toString());
                ps.setString(3, now.toString());
                ps.setString(4, now.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.debug("记录词汇失败 term={}: {}", term, e.getMessage());
            }
        }
    }

    @Override
    public double vocabularyBoost(String term) {
        if (term == null || term.isBlank()) {
            return 1.0;
        }
        String normalized = term.toLowerCase(Locale.ROOT).trim();
        Integer freq = vocabulary.get(normalized);
        return freq == null ? 1.0 : 1.0 + Math.min(freq, 10) * 0.05;
    }

    private double computeVocabBoost(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 1.0;
        }
        double boost = 1.0;
        for (String kw : keywords) {
            boost *= vocabularyBoost(kw);
        }
        return Math.min(boost, 2.5);
    }

    // ==================== 护栏 ====================

    @Override
    public int cleanupExpired() {
        int deleted = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM agent_memory_entries WHERE status = 'EXPIRED' " +
                        "OR (status = 'PENDING' AND pending_expires_at IS NOT NULL " +
                        "AND pending_expires_at < ?)")) {
            ps.setString(1, Instant.now().toString());
            deleted = ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清理过期记忆失败: {}", e.getMessage());
        }
        return deleted;
    }

    @Override
    public Optional<AgentMemoryEntry> findSimilar(String content, List<String> keywords, double threshold) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        // threshold <= 0 表示无阈值限制（返回最相似的）；> 0 用指定阈值；默认用 similarityThreshold
        double effectiveThreshold = threshold > 0 ? threshold : 0.0;
        // 先用 OR 逻辑找候选；若无结果退回到原始 content 的简单 MATCH
        String ftsQuery = buildFtsQueryOr(content, keywords);
        if (ftsQuery.isBlank()) {
            return Optional.empty();
        }
        Optional<AgentMemoryEntry> result = findSimilarByQuery(ftsQuery, effectiveThreshold);
        if (result.isPresent()) {
            return result;
        }
        // 退回：用 buildFtsQuery（AND 语义）再试一次
        String andQuery = buildFtsQuery(content);
        if (!andQuery.isBlank() && !andQuery.equals(ftsQuery)) {
            return findSimilarByQuery(andQuery, effectiveThreshold);
        }
        return Optional.empty();
    }

    private Optional<AgentMemoryEntry> findSimilarByQuery(String ftsQuery, double threshold) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT e.*, bm25(agent_memory_fts) AS bm25_score " +
                        "FROM agent_memory_fts f JOIN agent_memory_entries e ON e.id = f.id " +
                        "WHERE agent_memory_fts MATCH ? " +
                        "ORDER BY bm25_score ASC LIMIT 1")) {
            ps.setString(1, ftsQuery);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double bm25 = rs.getDouble("bm25_score");
                    double normalizedScore = 1.0 / (1.0 + Math.exp(bm25));
                    if (normalizedScore >= threshold) {
                        return Optional.of(resultSetToEntry(rs));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("findSimilarByQuery 失败 query={}: {}", ftsQuery, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 构造 FTS5 OR 查询（用于 findSimilar，只要有任意 token 匹配即返回候选）。
     */
    private String buildFtsQueryOr(String content, List<String> keywords) {
        Set<String> tokens = new LinkedHashSet<>();
        if (content != null && !content.isBlank()) {
            tokens.addAll(MemoryQueryTokenizer.tokenize(content));
        }
        if (keywords != null) {
            for (String kw : keywords) {
                if (kw != null && !kw.isBlank() && kw.trim().length() >= 2) {
                    tokens.add(kw.toLowerCase(Locale.ROOT).trim());
                }
            }
        }
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank() || token.length() < 2) {
                continue;
            }
            String safe = token.replace("\"", "");
            if (safe.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" OR ");
            }
            sb.append("\"").append(safe).append("\"");
        }
        return sb.toString();
    }

    // ==================== 辅助方法 ====================

    private AgentMemoryEntry resultSetToEntry(ResultSet rs) throws SQLException {
        return AgentMemoryEntry.builder()
                .id(rs.getString("id"))
                .content(rs.getString("content"))
                .keywords(AgentMemoryEntry.parseKeywords(rs.getString("keywords_json")))
                .type(AgentMemoryEntry.MemoryType.valueOf(rs.getString("type")))
                .scope(AgentMemoryEntry.MemoryScope.valueOf(rs.getString("scope")))
                .project(rs.getString("project"))
                .confidence(rs.getDouble("confidence"))
                .source(AgentMemoryEntry.MemorySource.valueOf(rs.getString("source")))
                .status(AgentMemoryEntry.MemoryStatus.valueOf(rs.getString("status")))
                .pendingExpiresAt(parseInstant(rs.getString("pending_expires_at")))
                .tokenCount(rs.getInt("token_count"))
                .accessCount(rs.getInt("access_count"))
                .lastAccessedAt(parseInstant(rs.getString("last_accessed_at")))
                .createdAt(parseInstant(rs.getString("created_at")))
                .updatedAt(parseInstant(rs.getString("updated_at")))
                .build();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构造 FTS5 MATCH 查询字符串。
     * 简单策略：用 jieba 分词后，把每个 token 用空格拼接（FTS5 默认 OR 语义）。
     * 对包含特殊字符的 token 用双引号包裹，避免语法错误。
     */
    private String buildFtsQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank() || token.length() < 2) {
                continue;
            }
            String safe = token.replace("\"", "");
            if (safe.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("\"").append(safe).append("\"");
        }
        return sb.toString();
    }

    private static String escapeSql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private void updateAccessCounts(List<MemorySearchResult> results) {
        if (results.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE agent_memory_entries SET access_count = access_count + 1, " +
                        "last_accessed_at = ? WHERE id = ?")) {
            for (MemorySearchResult r : results) {
                ps.setString(1, now.toString());
                ps.setString(2, r.entry().getId());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.debug("更新 access_count 失败: {}", e.getMessage());
        }
    }
}
