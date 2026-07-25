package com.bettercli.memory;

import com.bettercli.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 粘性会话轮换：/clear 时换新 session id、空检查点文件、新会话索引器。
 * CLI 与 TUI 共用，避免只清内存、重启又 Resume 回旧会话。
 */
public final class StickySessionRotator {

    private static final Logger log = LoggerFactory.getLogger(StickySessionRotator.class);

    private final SessionIdStore idStore;
    private final SessionMessageStore messageStore;
    private final AtomicReference<SessionCheckpointStore> checkpointStoreRef;
    private final AtomicReference<SessionMessageIndexer> indexerRef;
    private final Agent agent;
    private final Supplier<String> projectPathSupplier;

    public StickySessionRotator(SessionIdStore idStore,
                                SessionMessageStore messageStore,
                                AtomicReference<SessionCheckpointStore> checkpointStoreRef,
                                AtomicReference<SessionMessageIndexer> indexerRef,
                                Agent agent,
                                Supplier<String> projectPathSupplier) {
        this.idStore = Objects.requireNonNull(idStore);
        this.messageStore = messageStore;
        this.checkpointStoreRef = Objects.requireNonNull(checkpointStoreRef);
        this.indexerRef = Objects.requireNonNull(indexerRef);
        this.agent = Objects.requireNonNull(agent);
        this.projectPathSupplier = Objects.requireNonNull(projectPathSupplier);
    }

    public RotateResult rotate() {
        boolean forcedId = isSessionIdForced();
        String newId = forcedId ? idStore.currentId() : idStore.rotate();
        try {
            Path path = idStore.checkpointPathFor(newId);
            // 固定 SESSION_ID 时 truncate 同文件，保证 Resume 为空
            if (forcedId && Files.isRegularFile(path)) {
                Files.writeString(path, "");
            }
            SessionCheckpointStore store = new SessionCheckpointStore(path);
            checkpointStoreRef.set(store);
            // /clear 轮换：只换绑，禁止 Resume，防止空文件异常时把旧内容灌回
            agent.setSessionCheckpointStore(store, false);
            if (messageStore != null) {
                SessionMessageIndexer old = indexerRef.getAndSet(null);
                if (old != null) {
                    try {
                        old.close();
                    } catch (Exception e) {
                        log.warn("close old session indexer failed: {}", e.toString());
                    }
                }
                String project = projectPathSupplier.get();
                if (project == null || project.isBlank()) {
                    project = Path.of(".").toAbsolutePath().normalize().toString();
                }
                SessionMessageIndexer neu = new SessionMessageIndexer(messageStore, newId, project);
                indexerRef.set(neu);
                agent.setSessionMessageIndexer(neu);
            }
        } catch (Exception e) {
            log.warn("rotate sticky session failed: {}", e.toString());
            return new RotateResult(newId, false, "轮换会话检查点失败: " + e.getMessage());
        }
        if (forcedId) {
            return new RotateResult(newId, true,
                    "已清空内存与会话检查点；当前设置了固定 SESSION_ID，文件仍为同一会话名。");
        }
        return new RotateResult(newId, true, null);
    }

    private static boolean isSessionIdForced() {
        String prop = System.getProperty("bettercli.session.id");
        String env = System.getenv("BETTERCLI_SESSION_ID");
        return (prop != null && !prop.isBlank()) || (env != null && !env.isBlank());
    }

    public record RotateResult(String sessionId, boolean ok, String warning) {
    }
}
