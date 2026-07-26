package com.bettercli.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单 Agent 实例的 skill 注入缓冲区。
 *
 * <p>生命周期：LLM 调 {@code load_skill} → {@link #push} → 工具批结束后 / 下次 LLM 调用前
 * {@link #drain} 成独立 user 消息写入 conversationHistory（同轮即可生效）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>drain 是一次性消费（防止跨轮重复注入）</li>
 *   <li>同一会话内最多保留 3 个 skill body（超出 LRU 淘汰最旧）</li>
 *   <li>同一 skill 重复 push 会替换旧 body 并刷新到末尾</li>
 *   <li>/clear 命令调 {@link #clear()} 复位</li>
 * </ul>
 */
public final class SkillContextBuffer {

    public static final String INJECTION_HEADING_PREFIX = "## 已加载 Skill：";

    private static final int MAX_SKILLS = 3;

    private final Map<String, String> entries = new LinkedHashMap<>();

    public synchronized void push(String skillName, String body) {
        if (skillName == null || skillName.isBlank() || body == null) {
            return;
        }
        entries.remove(skillName);
        entries.put(skillName, body);
        while (entries.size() > MAX_SKILLS) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    /**
     * 取出全部已积累 skill body 并清空。返回拼接好的 markdown 段，可直接作为独立 user 消息
     * 或前置到用户输入前。
     */
    public synchronized String drain() {
        if (entries.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(entries.entrySet());
        entries.clear();

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : snapshot) {
            sb.append(INJECTION_HEADING_PREFIX).append(e.getKey()).append('\n')
                    .append(e.getValue().trim()).append('\n')
                    .append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
