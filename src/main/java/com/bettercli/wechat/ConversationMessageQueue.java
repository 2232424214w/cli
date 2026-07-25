package com.bettercli.wechat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 conversationId 隔离的 FIFO 消息队列（对齐 1024 长任务文档：消息串行化）。
 *
 * <p>同 key 内严格 FIFO；不同 conversationId 互不影响。
 * {@link #removeExpired} 可从任意位置删除超时条目，避免队中卡住堵死后继。
 */
public final class ConversationMessageQueue<T> {

    public record Ticket<V>(String conversationId, String entryId, Instant enqueuedAt, V payload) {
        public Ticket {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        }
    }

    /** @param position 1-based 队内位置；{@code isHead} 表示入队后即为队首 */
    public record EnqueueResult<V>(Ticket<V> ticket, int position, boolean isHead) {
    }

    private final Map<String, Object> lockMap = new ConcurrentHashMap<>();
    private final Map<String, List<Ticket<T>>> queues = new ConcurrentHashMap<>();

    public EnqueueResult<T> enqueue(String conversationId, T payload) {
        String key = normalizeKey(conversationId);
        Ticket<T> ticket = new Ticket<>(key, newEntryId(), Instant.now(), payload);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.computeIfAbsent(key, k -> new ArrayList<>());
            q.add(ticket);
            int position = q.size();
            return new EnqueueResult<>(ticket, position, position == 1);
        }
    }

    public Optional<Ticket<T>> peek(String conversationId) {
        String key = normalizeKey(conversationId);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.get(key);
            if (q == null || q.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(q.get(0));
        }
    }

    /** 弹出队首；空则 empty。应对标 finally 出队。 */
    public Optional<Ticket<T>> dequeue(String conversationId) {
        String key = normalizeKey(conversationId);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.get(key);
            if (q == null || q.isEmpty()) {
                return Optional.empty();
            }
            Ticket<T> head = q.remove(0);
            if (q.isEmpty()) {
                queues.remove(key);
            }
            return Optional.of(head);
        }
    }

    /** 从任意位置按 entryId 删除（超时 / 用户撤销）。 */
    public boolean remove(String conversationId, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        String key = normalizeKey(conversationId);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.get(key);
            if (q == null || q.isEmpty()) {
                return false;
            }
            boolean removed = q.removeIf(t -> entryId.equals(t.entryId()));
            if (q.isEmpty()) {
                queues.remove(key);
            }
            return removed;
        }
    }

    public List<Ticket<T>> removeExpired(String conversationId, Duration maxWait) {
        return removeExpired(conversationId, maxWait, Instant.now());
    }

    /** 可注入 now，便于单测。 */
    List<Ticket<T>> removeExpired(String conversationId, Duration maxWait, Instant now) {
        if (maxWait == null || maxWait.isNegative() || maxWait.isZero() || now == null) {
            return List.of();
        }
        String key = normalizeKey(conversationId);
        Instant deadline = now.minus(maxWait);
        List<Ticket<T>> expired = new ArrayList<>();
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.get(key);
            if (q == null || q.isEmpty()) {
                return List.of();
            }
            Iterator<Ticket<T>> it = q.iterator();
            while (it.hasNext()) {
                Ticket<T> t = it.next();
                if (!t.enqueuedAt().isAfter(deadline)) {
                    expired.add(t);
                    it.remove();
                }
            }
            if (q.isEmpty()) {
                queues.remove(key);
            }
        }
        return List.copyOf(expired);
    }

    public int size(String conversationId) {
        String key = normalizeKey(conversationId);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.get(key);
            return q == null ? 0 : q.size();
        }
    }

    public boolean isEmpty(String conversationId) {
        return size(conversationId) == 0;
    }

    public List<Ticket<T>> snapshot(String conversationId) {
        String key = normalizeKey(conversationId);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.get(key);
            if (q == null || q.isEmpty()) {
                return List.of();
            }
            return List.copyOf(q);
        }
    }

    /** 清空指定会话队列。 */
    public int clear(String conversationId) {
        String key = normalizeKey(conversationId);
        synchronized (lockFor(key)) {
            List<Ticket<T>> q = queues.remove(key);
            return q == null ? 0 : q.size();
        }
    }

    private Object lockFor(String key) {
        return lockMap.computeIfAbsent(key, k -> new Object());
    }

    private static String normalizeKey(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "_default";
        }
        return conversationId.trim();
    }

    private static String newEntryId() {
        return "q_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 诊断：各会话队列深度。 */
    public Map<String, Integer> depths() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : List.copyOf(queues.keySet())) {
            out.put(key, size(key));
        }
        return out;
    }
}
