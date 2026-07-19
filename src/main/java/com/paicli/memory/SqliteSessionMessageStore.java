package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * SQLite FTS5 实现的历史会话消息存储（对标美团 1024 Agent session_messages + session_search）。
 *
 * 设计参考：docs/memory-system-design.md §3.3 / §4.4 / §5.3
 *
 * 特性：
 * - SQLite FTS5 BM25 全文检索
 * - 五阶段管道：检索 → 按会话分组 → 加载完整 → 截断预览 → 返回
 * - 从 ~/.paicli/history/session_*.jsonl 迁移（幂等，写 marker 文件）
 * - 按项目 / 时间 / 角色过滤
 */
public class SqliteSessionMessageStore implements SessionMessageStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteSessionMessageStore.class);

    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    private static final String DB_FILE = "session_messages.db";
    private static final String MIGRATION_MARKER = ".session-messages-migrated";
    private static final int BM25_TOPK_MULTIPLIER = 10;

    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqliteSessionMessageStore() throws SQLException {
        this(resolveMemoryDir());
    }

    public SqliteSessionMessageStore(java.io.File memoryDir) throws SQLException {
        if (!memoryDir.exists() && !memoryDir.mkdirs()) {
            throw new SQLException("无法创建记忆目录: " + memoryDir);
        }
        String dbPath = new java.io.File(memoryDir, DB_FILE).getAbsolutePath();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS session_messages (
                        id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tool_calls_json TEXT,
                        tool_call_id TEXT,
                        project TEXT,
                        created_at TEXT NOT NULL,
                        token_count INTEGER NOT NULL DEFAULT 0
                    )
                    """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sm_conversation ON session_messages(conversation_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sm_project ON session_messages(project)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sm_created ON session_messages(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sm_role ON session_messages(role)");

            stmt.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS session_messages_fts USING fts5(
                        id UNINDEXED,
                        content,
                        tokenize = 'unicode61'
                    )
                    """);

            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS session_messages_ai AFTER INSERT ON session_messages BEGIN
                        INSERT INTO session_messages_fts(id, content) VALUES (new.id, new.content);
                    END
                    """);
            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS session_messages_ad AFTER DELETE ON session_messages BEGIN
                        DELETE FROM session_messages_fts WHERE id = old.id;
                    END
                    """);
            stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS session_messages_au AFTER UPDATE ON session_messages BEGIN
                        DELETE FROM session_messages_fts WHERE id = old.id;
                        INSERT INTO session_messages_fts(id, content) VALUES (new.id, new.content);
                    END
                    """);
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
        return new java.io.File(new java.io.File(System.getProperty("user.home"), ".paicli"), "memory");
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("关闭 session_messages 数据库失败: {}", e.getMessage());
        }
    }

    // ==================== CRUD ====================

    @Override
    public void index(SessionMessage message) {
        Objects.requireNonNull(message, "message");
        String sql = """
                INSERT OR IGNORE INTO session_messages
                (id, conversation_id, role, content, tool_calls_json, tool_call_id, project, created_at, token_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, message.getId());
            ps.setString(2, message.getConversationId());
            ps.setString(3, message.getRole());
            ps.setString(4, message.getContent());
            ps.setString(5, message.getToolCallsJson());
            ps.setString(6, message.getToolCallId());
            ps.setString(7, message.getProject());
            ps.setString(8, message.getCreatedAt() == null ? Instant.now().toString() : message.getCreatedAt().toString());
            ps.setInt(9, message.getTokenCount());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("索引会话消息失败: {}", e.getMessage());
        }
    }

    @Override
    public int indexBatch(List<SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int count = 0;
        String sql = """
                INSERT OR IGNORE INTO session_messages
                (id, conversation_id, role, content, tool_calls_json, tool_call_id, project, created_at, token_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (SessionMessage message : messages) {
                if (message == null) continue;
                ps.setString(1, message.getId());
                ps.setString(2, message.getConversationId());
                ps.setString(3, message.getRole());
                ps.setString(4, message.getContent());
                ps.setString(5, message.getToolCallsJson());
                ps.setString(6, message.getToolCallId());
                ps.setString(7, message.getProject());
                ps.setString(8, message.getCreatedAt() == null ? Instant.now().toString() : message.getCreatedAt().toString());
                ps.setInt(9, message.getTokenCount());
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("批量索引会话消息失败: {}", e.getMessage());
        }
        return count;
    }

    @Override
    public List<SessionMessage> loadConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return List.of();
        String sql = """
                SELECT id, conversation_id, role, content, tool_calls_json, tool_call_id, project, created_at, token_count
                FROM session_messages
                WHERE conversation_id = ?
                ORDER BY created_at ASC
                """;
        List<SessionMessage> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(resultSetToMessage(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("加载会话失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<String> listConversations(int limit) {
        int safeLimit = limit > 0 ? limit : 100;
        String sql = """
                SELECT conversation_id, MAX(created_at) AS last_active
                FROM session_messages
                GROUP BY conversation_id
                ORDER BY last_active DESC
                LIMIT ?
                """;
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("conversation_id"));
                }
            }
        } catch (SQLException e) {
            log.warn("列出会话失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public int deleteConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return 0;
        String sql = "DELETE FROM session_messages WHERE conversation_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("删除会话失败: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int size() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM session_messages")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public int conversationCount() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(DISTINCT conversation_id) FROM session_messages")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private SessionMessage resultSetToMessage(ResultSet rs) throws SQLException {
        return SessionMessage.builder()
                .id(rs.getString("id"))
                .conversationId(rs.getString("conversation_id"))
                .role(rs.getString("role"))
                .content(rs.getString("content"))
                .toolCallsJson(rs.getString("tool_calls_json"))
                .toolCallId(rs.getString("tool_call_id"))
                .project(rs.getString("project"))
                .createdAt(parseInstant(rs.getString("created_at")))
                .tokenCount(rs.getInt("token_count"))
                .build();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 五阶段检索管道 ====================

    @Override
    public List<SessionMessageSearchResult> search(SessionMessageSearchQuery query) {
        if (query == null || query.getQuery() == null || query.getQuery().isBlank()) {
            return List.of();
        }
        String ftsQuery = buildFtsQuery(query.getQuery());
        if (ftsQuery.isBlank()) {
            return List.of();
        }

        // ① BM25 全文检索 → topK = limit × 10
        int topK = query.getLimit() * BM25_TOPK_MULTIPLIER;
        List<Hit> hits = bm25Search(ftsQuery, query, topK);
        if (hits.isEmpty()) {
            return List.of();
        }

        // ② 按 conversation_id 分组，取每个会话最高 BM25 分
        Map<String, List<Hit>> grouped = new LinkedHashMap<>();
        for (Hit hit : hits) {
            grouped.computeIfAbsent(hit.conversationId, k -> new ArrayList<>()).add(hit);
        }

        // 按会话最高分降序，取 Top N
        List<Map.Entry<String, List<Hit>>> sortedSessions = new ArrayList<>(grouped.entrySet());
        sortedSessions.sort((a, b) -> Double.compare(bestScore(b.getValue()), bestScore(a.getValue())));
        int sessionLimit = Math.min(query.getLimit(), sortedSessions.size());

        // ③ ④ ⑤ 加载完整会话 + 截断预览 + 组装结果
        List<SessionMessageSearchResult> results = new ArrayList<>();
        for (int i = 0; i < sessionLimit; i++) {
            Map.Entry<String, List<Hit>> entry = sortedSessions.get(i);
            String conversationId = entry.getKey();
            List<Hit> sessionHits = entry.getValue();
            double bestScore = bestScore(sessionHits);

            List<SessionMessage> fullConv = loadConversation(conversationId);
            if (fullConv.isEmpty()) continue;

            // 构造命中消息预览
            List<SessionMessageSearchResult.MatchedMessage> matched = new ArrayList<>();
            int topKPerSession = Math.min(query.getTopKPerSession(), sessionHits.size());
            for (int j = 0; j < topKPerSession; j++) {
                Hit h = sessionHits.get(j);
                String preview = truncate(h.content, query.getPreviewChars());
                matched.add(new SessionMessageSearchResult.MatchedMessage(
                        h.id, h.role, preview, normalizeBm25(h.bm25Score), h.createdAt));
            }

            SessionMessage first = fullConv.get(0);
            SessionMessage last = fullConv.get(fullConv.size() - 1);
            results.add(new SessionMessageSearchResult(
                    conversationId,
                    first.getProject(),
                    first.getCreatedAt(),
                    last.getCreatedAt(),
                    fullConv.size(),
                    normalizeBm25(bestScore),
                    matched,
                    fullConv
            ));
        }
        return results;
    }

    private record Hit(String id, String conversationId, String role, String content,
                       double bm25Score, Instant createdAt) {}

    private List<Hit> bm25Search(String ftsQuery, SessionMessageSearchQuery query, int topK) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id, m.conversation_id, m.role, m.content, m.created_at,
                       bm25(session_messages_fts) AS bm25_score
                FROM session_messages_fts f
                JOIN session_messages m ON f.id = m.id
                WHERE session_messages_fts MATCH ?
                """);
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        params.add(ftsQuery);

        if (query.getProject() != null && !query.getProject().isBlank()) {
            conditions.add("m.project = ?");
            params.add(query.getProject());
        }
        if (query.getRoleFilter() != null && !query.getRoleFilter().isBlank()) {
            conditions.add("m.role = ?");
            params.add(query.getRoleFilter());
        }
        if (query.getSince() != null) {
            conditions.add("m.created_at >= ?");
            params.add(query.getSince().toString());
        }
        if (query.getUntil() != null) {
            conditions.add("m.created_at <= ?");
            params.add(query.getUntil().toString());
        }
        if (!conditions.isEmpty()) {
            sql.append(" AND ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY bm25_score ASC LIMIT ?");

        List<Hit> hits = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.setInt(params.size() + 1, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new Hit(
                            rs.getString("id"),
                            rs.getString("conversation_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getDouble("bm25_score"),
                            parseInstant(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            log.warn("会话消息 BM25 检索失败: {}", e.getMessage());
        }
        return hits;
    }

    private static double bestScore(List<Hit> hits) {
        double best = Double.MAX_VALUE;
        for (Hit h : hits) {
            if (h.bm25Score < best) best = h.bm25Score;
        }
        return best;
    }

    /**
     * FTS5 的 bm25() 返回负值（越小越相关），归一化到 [0, 1]（越大越相关）。
     */
    private static double normalizeBm25(double rawBm25) {
        return 1.0 / (1.0 + Math.exp(rawBm25));
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    private String buildFtsQuery(String query) {
        if (query == null || query.isBlank()) return "";
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        if (tokens.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank() || token.length() < 2) continue;
            String safe = token.replace("\"", "");
            if (safe.isBlank()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append("\"").append(safe).append("\"");
        }
        return sb.toString();
    }

    // ==================== 从 session_*.jsonl 迁移 ====================

    @Override
    public int migrateFromJsonl(java.io.File historyDir) throws IOException {
        if (historyDir == null || !historyDir.isDirectory()) return 0;
        java.io.File marker = new java.io.File(historyDir, MIGRATION_MARKER);
        if (marker.exists()) {
            log.debug("session_*.jsonl 已迁移过，跳过");
            return 0;
        }

        int totalMigrated = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir.toPath(), "session_*.jsonl")) {
            List<Path> files = new ArrayList<>(StreamSupport.stream(stream.spliterator(), false).toList());
            for (Path file : files) {
                String conversationId = file.getFileName().toString().replace(".jsonl", "");
                totalMigrated += migrateOneJsonl(file, conversationId);
            }
        }

        // 写 marker
        try {
            Files.writeString(marker.toPath(), "migrated at " + Instant.now() + "\n");
        } catch (Exception e) {
            log.debug("写入会话迁移标记失败: {}", e.getMessage());
        }

        if (totalMigrated > 0) {
            log.info("从 session_*.jsonl 迁移了 {} 条消息到 SQLite", totalMigrated);
        }
        return totalMigrated;
    }

    private int migrateOneJsonl(Path file, String conversationId) throws IOException {
        List<SessionMessage> batch = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = mapper.readValue(line, Map.class);
                    String role = String.valueOf(record.getOrDefault("role", "user"));
                    String content = String.valueOf(record.getOrDefault("content", ""));
                    long timestamp = record.get("timestamp") instanceof Number n ? n.longValue() : System.currentTimeMillis();
                    Instant createdAt = Instant.ofEpochMilli(timestamp);
                    String id = conversationId + "-" + index;
                    batch.add(SessionMessage.builder()
                            .id(id)
                            .conversationId(conversationId)
                            .role(role)
                            .content(content)
                            .createdAt(createdAt)
                            .build());
                    index++;
                } catch (Exception e) {
                    log.debug("跳过无法解析的 session 消息行: {}", e.getMessage());
                }
            }
        }
        if (batch.isEmpty()) return 0;
        return indexBatch(batch);
    }
}
