package com.bettercli.subagent;

import java.util.List;

/**
 * 把已加载的 Custom SubAgent 渲染成主 Agent system prompt 索引段，供语义匹配。
 *
 * <p>预算约束（命中即截断）：
 * <ul>
 *   <li>单条 description ≤ 500 codepoint</li>
 *   <li>条数 ≤ 20（按 name 字典序保留前 20）</li>
 *   <li>总段大小 ≤ 4096 字符</li>
 * </ul>
 */
public final class CustomSubAgentIndexFormatter {

    public static final int MAX_DESCRIPTION_CODEPOINTS = 500;
    public static final int MAX_AGENTS = 20;
    public static final int MAX_INDEX_CHARS = 4096;

    private CustomSubAgentIndexFormatter() {
    }

    public static String format(List<CustomSubAgentDefinition> agents) {
        if (agents == null || agents.isEmpty()) {
            return "";
        }

        List<CustomSubAgentDefinition> effective = agents;
        if (agents.size() > MAX_AGENTS) {
            effective = agents.stream()
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .limit(MAX_AGENTS)
                    .toList();
            System.err.println("⚠️ 已检测到 " + agents.size()
                    + " 个 Custom SubAgent，仅前 " + MAX_AGENTS + " 个进入 system prompt 索引");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用 Custom Subagents（按需调用 run_subagent 委托）\n\n");

        for (CustomSubAgentDefinition agent : effective) {
            String desc = truncateByCodepoint(agent.description().trim(), MAX_DESCRIPTION_CODEPOINTS);
            sb.append("- **").append(agent.name()).append("**：").append(desc).append('\n');
        }

        sb.append('\n');
        sb.append("判断准则：当用户任务匹配某个子 Agent 的专长描述，或用户自然语言明确点名某个 name 时，")
                .append("调用 run_subagent(name, task) 委托执行；未命中则用普通工具自行处理。")
                .append("用户也可用消息前缀 `/subagent:name …` 或 `/sa:name …` 硬指定（由系统入站路由处理，不必再调本工具）。")
                .append("不要用空格形式 `/subagent name task`（那是管理命令命名空间）。")
                .append("run_subagent 默认同轮前台异步（可并行，批次结束回填）；"
                        + "mode=background 时先 accepted，完成后写入完成通知并 bg-react 汇总。"
                        + "微信通道默认 background。\n");

        if (sb.length() > MAX_INDEX_CHARS) {
            String truncated = sb.substring(0, MAX_INDEX_CHARS) + "\n...(Custom Subagent 索引段被截断)\n";
            System.err.println("⚠️ Custom Subagent 索引段超过 " + MAX_INDEX_CHARS + " 字符，已截断");
            return truncated;
        }
        return sb.toString();
    }

    static String truncateByCodepoint(String s, int limit) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= limit) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < s.length() && count < limit) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            count++;
        }
        return sb.toString() + "...";
    }
}
