package com.bettercli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * 粘性会话 ID：让压缩检查点跨 CLI 重启可 Resume（对标 1024 session 续跑）。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>{@code -Dbettercli.session.id} / {@code BETTERCLI_SESSION_ID}</li>
 *   <li>{@code ~/.bettercli/history/active-session.id}（若存在且非空）</li>
 *   <li>新生成 UUID 并写入 active-session.id</li>
 * </ol>
 *
 * <p>{@link #rotate()} 用于 {@code /clear}：生成新 ID，旧 jsonl 保留供审计，新会话从空检查点开始。
 */
public final class SessionIdStore {

    private static final Logger log = LoggerFactory.getLogger(SessionIdStore.class);

    private final Path activeIdFile;
    private String currentId;

    public SessionIdStore() {
        this(Path.of(System.getProperty("user.home"), ".bettercli", "history", "active-session.id"));
    }

    public SessionIdStore(Path activeIdFile) {
        this.activeIdFile = activeIdFile;
    }

    public synchronized String currentId() {
        if (currentId == null || currentId.isBlank()) {
            currentId = resolveOrCreate(false);
        }
        return currentId;
    }

    /** 启动时解析；{@code forceNew=true} 时忽略已有文件强制新建（测试用）。 */
    public synchronized String resolveOrCreate(boolean forceNew) {
        String fromEnv = firstNonBlank(
                System.getProperty("bettercli.session.id"),
                System.getenv("BETTERCLI_SESSION_ID")
        );
        if (fromEnv != null) {
            currentId = sanitize(fromEnv);
            persist(currentId);
            return currentId;
        }
        if (!forceNew) {
            try {
                if (Files.isRegularFile(activeIdFile)) {
                    String existing = Files.readString(activeIdFile, StandardCharsets.UTF_8).trim();
                    if (!existing.isBlank()) {
                        currentId = sanitize(existing);
                        return currentId;
                    }
                }
            } catch (Exception e) {
                log.warn("read active session id failed: {}", e.toString());
            }
        }
        currentId = sanitize(UUID.randomUUID().toString());
        persist(currentId);
        return currentId;
    }

    /** /clear：轮换会话 ID，下次写入新的 session-*.jsonl。 */
    public synchronized String rotate() {
        currentId = sanitize(UUID.randomUUID().toString());
        persist(currentId);
        log.info("rotated session id -> {}", currentId);
        return currentId;
    }

    public Path checkpointPathFor(String conversationId) {
        Path dir = activeIdFile.getParent() != null
                ? activeIdFile.getParent()
                : Path.of(System.getProperty("user.home"), ".bettercli", "history");
        return dir.resolve("session-" + sanitize(conversationId) + ".jsonl");
    }

    private void persist(String id) {
        try {
            Path parent = activeIdFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(activeIdFile, id + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("persist active session id failed: {}", e.toString());
        }
    }

    private static String sanitize(String raw) {
        String s = raw == null ? "" : raw.trim();
        // 文件名安全：只留常见安全字符
        s = s.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (s.isBlank()) {
            s = UUID.randomUUID().toString();
        }
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
