package com.bettercli.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话级结构化记事本（Anthropic structured note-taking）：
 * 把关键决策 / 约束 / 路径 / 待办 offload 到 context 外，压缩后仍可通过工具读回。
 *
 * <p>会话内存态，不跨进程持久化；{@code /clear} 清空。
 */
public class SessionNotebook {

    public record Note(long id, String title, String body, long updatedAtMs) {
    }

    private final AtomicLong seq = new AtomicLong();
    private final CopyOnWriteArrayList<Note> notes = new CopyOnWriteArrayList<>();

    public Note append(String title, String body) {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.trim();
        if (t.isEmpty() && b.isEmpty()) {
            throw new IllegalArgumentException("title 与 body 不能同时为空");
        }
        if (t.isEmpty()) {
            t = "note-" + (notes.size() + 1);
        }
        Note note = new Note(seq.incrementAndGet(), t, b, System.currentTimeMillis());
        notes.add(note);
        return note;
    }

    public Note update(long id, String title, String body) {
        for (int i = 0; i < notes.size(); i++) {
            Note existing = notes.get(i);
            if (existing.id() == id) {
                String t = title == null || title.isBlank() ? existing.title() : title.trim();
                String b = body == null ? existing.body() : body.trim();
                Note updated = new Note(id, t, b, System.currentTimeMillis());
                notes.set(i, updated);
                return updated;
            }
        }
        throw new IllegalArgumentException("记事本中不存在 id=" + id);
    }

    public boolean delete(long id) {
        return notes.removeIf(n -> n.id() == id);
    }

    public void clear() {
        notes.clear();
    }

    public List<Note> list() {
        return List.copyOf(notes);
    }

    public List<Note> search(String query) {
        if (query == null || query.isBlank()) {
            return list();
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Note> hit = new ArrayList<>();
        for (Note n : notes) {
            if (n.title().toLowerCase(Locale.ROOT).contains(q)
                    || n.body().toLowerCase(Locale.ROOT).contains(q)) {
                hit.add(n);
            }
        }
        return hit;
    }

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public int size() {
        return notes.size();
    }

    /** 注入 system prompt / 工具回显用的可读视图 */
    public String formatView() {
        if (notes.isEmpty()) {
            return "(记事本为空)";
        }
        StringBuilder sb = new StringBuilder();
        for (Note n : notes) {
            sb.append("#").append(n.id()).append(" ").append(n.title()).append("\n");
            if (n.body() != null && !n.body().isBlank()) {
                sb.append(n.body()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** 短摘要，供 system prompt 注入（控制 token） */
    public String formatSummary(int maxNotes, int maxChars) {
        if (notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Session Notebook（结构化笔记，可用 notebook_read 读全文）：\n");
        int count = 0;
        for (Note n : notes) {
            if (count >= maxNotes) {
                sb.append("- …共 ").append(notes.size()).append(" 条，其余用 notebook_read 查看\n");
                break;
            }
            String line = "- #" + n.id() + " " + n.title();
            if (n.body() != null && !n.body().isBlank()) {
                String preview = n.body().length() > 80 ? n.body().substring(0, 80) + "…" : n.body();
                line += "：" + preview.replace('\n', ' ');
            }
            sb.append(line).append("\n");
            count++;
        }
        String out = sb.toString().trim();
        if (out.length() > maxChars) {
            return out.substring(0, maxChars) + "…";
        }
        return out;
    }
}
