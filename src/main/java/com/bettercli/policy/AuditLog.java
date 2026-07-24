package com.bettercli.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bettercli.browser.BrowserAuditMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 危险工具调用的结构化审计日志。
 *
 * 落盘策略：
 * - 一行一条 JSON（JSONL 格式），按天分文件 audit-YYYY-MM-DD.jsonl
 * - 默认目录 ~/.bettercli/audit，可通过 -Dbettercli.audit.dir 或 BETTERCLI_AUDIT_DIR 覆盖
 * - 写入失败只在 stderr 提示，不抛出，避免审计故障影响主流程
 *
 * 设计意图：
 * - 把 Agent 的"实际副作用"变成可回放的事实流
 * - 行为评估、差错复盘、监控告警的统一数据源
 *
 * 接入点：
 * - {@code allow}：危险工具执行成功
 * - {@code deny}：被 HITL 拒绝 / 跳过，或被策略层拦截
 * - {@code error}：工具执行抛异常或超时
 */
public class AuditLog {

    public static final String APPROVER_HITL = "hitl";
    public static final String APPROVER_POLICY = "policy";
    public static final String APPROVER_NONE = "none";
    public static final String APPROVER_MENTION = "mention";

    public static final String OUTCOME_ALLOW = "allow";
    public static final String OUTCOME_DENY = "deny";
    public static final String OUTCOME_ERROR = "error";

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final int MAX_FIELD_CHARS = 1000;

    private final Path auditDir;
    private final Object writeLock = new Object();

    public AuditLog() {
        this(defaultAuditDir());
    }

    public AuditLog(Path auditDir) {
        this.auditDir = auditDir;
    }

    public Path getAuditDir() {
        return auditDir;
    }

    public void record(AuditEntry entry) {
        if (entry == null) return;
        try {
            synchronized (writeLock) {
                Files.createDirectories(auditDir);
                Path file = todayFile();
                String json = mapper.writeValueAsString(entry);
                Files.writeString(file, json + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // 审计失败不能影响主流程
            System.err.println("⚠️ 审计日志写入失败: " + e.getMessage());
        }
    }

    /**
     * 读取今天审计文件最近 n 条记录，按写入顺序返回（最新的在末尾）。
     */
    public List<AuditEntry> readRecent(int n) {
        if (n <= 0) return List.of();
        Path file = todayFile();
        if (!Files.exists(file)) return List.of();

        try {
            List<String> lines = Files.readAllLines(file);
            int from = Math.max(0, lines.size() - n);
            List<AuditEntry> entries = new ArrayList<>();
            for (int i = from; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                try {
                    entries.add(mapper.readValue(line, new TypeReference<AuditEntry>() {}));
                } catch (Exception ignored) {
                    // 单行格式错误跳过，不影响其他记录
                }
            }
            return entries;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path todayFile() {
        return auditDir.resolve("audit-" + LocalDate.now().format(DATE_FMT) + ".jsonl");
    }

    private static Path defaultAuditDir() {
        String prop = System.getProperty("bettercli.audit.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("BETTERCLI_AUDIT_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".bettercli", "audit");
    }

    private static String truncate(String s) {
        if (s == null) return null;
        String sanitized = sanitize(s);
        return sanitized.length() <= MAX_FIELD_CHARS ? sanitized : sanitized.substring(0, MAX_FIELD_CHARS) + "...(truncated)";
    }

    static String sanitize(String s) {
        if (s == null) return null;
        String sanitized = s.replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***");
        // JSON / key=value：扩展关键词（api_key、access_token、credential 等）
        sanitized = sanitized.replaceAll(
                "(?i)(\"?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|"
                        + "credential|credentials|passwd|password|secret|token|authorization|key)\"?"
                        + "\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***$3");
        sanitized = sanitized.replaceAll(
                "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|"
                        + "credential|credentials|passwd|password|secret|token|authorization|key)\\b"
                        + "\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");
        // 环境变量赋值：FOO_API_KEY=... / BAR_SECRET: ...
        sanitized = sanitized.replaceAll(
                "(?i)(\\b[A-Z][A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|REFRESH[_-]?TOKEN|"
                        + "CLIENT[_-]?SECRET|SECRET|PASSWORD|TOKEN|CREDENTIALS?)\\b\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");
        // 进程环境块常见形态：KEY=value 且 KEY 含敏感后缀（已大写匹配；再补小写 env 名）
        sanitized = sanitized.replaceAll(
                "(?i)(\\b[a-z][a-z0-9_]*(?:api[_-]?key|access[_-]?token|secret|password|token)\\b\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");
        return sanitized;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditEntry(
            String timestamp,
            String tool,
            String args,
            String outcome,
            String reason,
            String approver,
            long durationMs,
            BrowserAuditMetadata metadata
    ) {
        public static AuditEntry allow(String tool, String args, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ALLOW, null, APPROVER_NONE, durationMs, null);
        }

        public static AuditEntry allow(String tool, String args, long durationMs, BrowserAuditMetadata metadata) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ALLOW, null, APPROVER_NONE, durationMs, metadata);
        }

        public static AuditEntry allowByMention(String tool, String args, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ALLOW, null, APPROVER_MENTION, durationMs, null);
        }

        public static AuditEntry denyByHitl(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_DENY, reason, APPROVER_HITL, durationMs, null);
        }

        public static AuditEntry denyByPolicy(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_DENY, reason, APPROVER_POLICY, durationMs, null);
        }

        public static AuditEntry denyByPolicy(String tool, String args, String reason, long durationMs,
                                              BrowserAuditMetadata metadata) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_DENY, reason, APPROVER_POLICY, durationMs, metadata);
        }

        public static AuditEntry error(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ERROR, reason, APPROVER_NONE, durationMs, null);
        }

        public static AuditEntry error(String tool, String args, String reason, long durationMs,
                                       BrowserAuditMetadata metadata) {
            return new AuditEntry(Instant.now().toString(), tool, truncate(args),
                    OUTCOME_ERROR, reason, APPROVER_NONE, durationMs, metadata);
        }
    }
}
