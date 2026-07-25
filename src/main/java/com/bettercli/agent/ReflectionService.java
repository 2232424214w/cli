package com.bettercli.agent;

import com.bettercli.tool.ToolRegistry.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 工具失败反思服务（阶段1：轻量反思，不额外调用 LLM）。
 *
 * <p>设计参考：OpenHands CriticMixin + Claude Code 错误恢复反螺旋。
 * 在 ReAct 循环 executeToolCalls 之后检测本轮工具结果，若出现失败/拒绝/超时，
 * 注入一条反思提示到 conversationHistory，引导 LLM 复述错误原因 + 改换策略，
 * 而非原样重试。不额外调用 LLM，零成本增量（仅多一条上下文）。
 *
 * <p>反螺旋：内置 consecutiveReflections 计数器，连续反思超过 maxConsecutive 阈值后
 * 停止反思，交给 AgentBudget 的 stagnation 检测兜底。借鉴 Claude Code
 * hasAttemptedReactiveCompact 的 one-shot 思路，避免"反思→失败→反思"死循环。
 *
 * <p>失败检测优先读 ToolExecutionResult.status；无显式状态时回退字符串前缀 + timedOut。
 *
 * <p>配置：bettercli.react.reflection.enabled（默认 true）/
 *         bettercli.react.reflection.max.consecutive（默认 2）
 */
public class ReflectionService {

    public enum Outcome { SUCCESS, FAILED, REJECTED, TIMEOUT }

    private final boolean enabled;
    private final int maxConsecutive;
    private int consecutiveReflections;

    public ReflectionService() {
        this(readBoolProperty("bettercli.react.reflection.enabled", true),
                readIntProperty("bettercli.react.reflection.max.consecutive", 2));
    }

    public ReflectionService(boolean enabled, int maxConsecutive) {
        this.enabled = enabled;
        this.maxConsecutive = Math.max(1, maxConsecutive);
    }

    /** 分类单个工具结果。优先结构化 status，再回退字符串前缀。 */
    public Outcome classify(ToolExecutionResult result) {
        if (result == null) {
            return Outcome.SUCCESS;
        }
        if (result.timedOut()
                || (result.status() != null
                && result.status().errorType() == com.bettercli.tool.ToolStatus.ErrorType.TIMEOUT)) {
            return Outcome.TIMEOUT;
        }
        if (result.status() != null && !result.status().success()) {
            return switch (result.status().errorType()) {
                case POLICY_DENIED -> Outcome.REJECTED;
                case TIMEOUT -> Outcome.TIMEOUT;
                case OK -> Outcome.SUCCESS;
                default -> Outcome.FAILED;
            };
        }
        String text = result.result() == null ? "" : result.result();
        if (text.startsWith("🛡️")) {
            return Outcome.REJECTED;
        }
        if (text.startsWith("❌")) {
            return Outcome.FAILED;
        }
        if (text.contains("失败:") || text.contains("失败 -")) {
            return Outcome.FAILED;
        }
        return Outcome.SUCCESS;
    }

    /**
     * 本轮有非 SUCCESS 时构造反思提示；无失败或已达反螺旋阈值返回 null。
     * 有状态：维护 consecutiveReflections 计数器。
     *
     * <p>反螺旋语义：本轮全成功则重置计数为 0；本轮有失败则计数 +1，
     * 超过 maxConsecutive 后停止反思（返回 null），交给 AgentBudget stagnation 兜底。
     */
    public String buildReflectionPrompt(List<ToolExecutionResult> results, int iteration) {
        if (!enabled || results == null || results.isEmpty()) {
            return null;
        }
        List<Failure> failures = new ArrayList<>();
        for (ToolExecutionResult r : results) {
            Outcome o = classify(r);
            if (o != Outcome.SUCCESS) {
                failures.add(new Failure(r.name(), o, firstLine(r.result())));
            }
        }
        if (failures.isEmpty()) {
            consecutiveReflections = 0;
            return null;
        }
        consecutiveReflections++;
        if (consecutiveReflections > maxConsecutive) {
            // 反螺旋：停止反思，交给 budget stagnation 兜底
            return null;
        }
        return formatPrompt(failures, iteration, consecutiveReflections);
    }

    private String formatPrompt(List<Failure> failures, int iteration, int attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("[反思提示] 第 ").append(iteration).append(" 轮工具调用出现失败")
                .append("（已连续反思 ").append(attempt).append("/").append(maxConsecutive).append(" 次）：\n");
        for (Failure f : failures) {
            sb.append("- ").append(f.name).append(" [").append(labelOf(f.outcome)).append("]: ")
                    .append(f.detail).append("\n");
        }
        sb.append("请先复述每个失败的原因，再改换策略（如改用项目内相对路径、缩小搜索范围、换用更安全的命令），")
                .append("不要原样重试同一调用。若需调整任务步骤，可调用 update_plan。");
        return sb.toString();
    }

    private static String labelOf(Outcome o) {
        return switch (o) {
            case REJECTED -> "策略拒绝";
            case TIMEOUT -> "超时";
            case FAILED -> "失败";
            case SUCCESS -> "成功";
        };
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        String line = nl > 0 ? text.substring(0, nl) : text;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private record Failure(String name, Outcome outcome, String detail) {}

    // ─── Multi-Agent 增量辩论路径（阶段 2：专门构造辩论上下文，仍由现有 Worker/Reviewer LLM 执行）───

    /**
     * 构造 Worker 增量修改上下文（对标辩论收敛：不从头重做，只改审查指出的点）。
     *
     * @param baseContext   原步骤上下文（依赖产物等）
     * @param previousResult 上一轮 Worker 产物
     * @param reviewIssues  Reviewer 指出的具体问题
     * @param round         当前辩论轮次（1-based）
     */
    public static String buildIncrementalDebateContext(String baseContext,
                                                       String previousResult,
                                                       String reviewIssues,
                                                       int round) {
        StringBuilder sb = new StringBuilder();
        if (baseContext != null && !baseContext.isBlank()) {
            sb.append(baseContext).append("\n\n");
        }
        sb.append("[增量辩论 · 第 ").append(Math.max(1, round)).append(" 轮]\n");
        sb.append("审查者指出以下具体问题，请在上一版结果基础上增量修改，")
                .append("不要推倒重来，不要重复已正确的部分：\n");
        sb.append(reviewIssues == null || reviewIssues.isBlank()
                ? "（未给出具体问题，请自我检查并改进）\n"
                : reviewIssues + "\n");
        if (previousResult != null && !previousResult.isBlank()) {
            String preview = previousResult.length() > 1500
                    ? previousResult.substring(0, 1500) + "..."
                    : previousResult;
            sb.append("\n上一版执行结果：\n").append(preview).append("\n");
        }
        return sb.toString();
    }

    /**
     * 收敛判断：审查 JSON 显式 {@code converged: true}，或本轮 issues 与上轮高度相似
     *（简单归一化后相等 / 互相包含），表示继续重试无增量收益。
     */
    public static boolean isDebateConverged(String reviewContent, String previousIssues) {
        if (reviewContent != null && !reviewContent.isBlank()) {
            String lower = reviewContent.toLowerCase();
            if (lower.contains("\"converged\": true") || lower.contains("\"converged\":true")
                    || lower.contains("\"converged\": true")) {
                return true;
            }
            // 中文显式收敛信号
            if (reviewContent.contains("已收敛") || reviewContent.contains("无需再改")
                    || reviewContent.contains("继续修改收益有限")) {
                return true;
            }
        }
        if (previousIssues == null || previousIssues.isBlank()) {
            return false;
        }
        String curr = normalizeIssues(extractIssuesHeuristic(reviewContent));
        String prev = normalizeIssues(previousIssues);
        if (curr.isEmpty() || prev.isEmpty()) {
            return false;
        }
        return curr.equals(prev) || curr.contains(prev) || prev.contains(curr);
    }

    private static String extractIssuesHeuristic(String reviewContent) {
        if (reviewContent == null) return "";
        // 粗提取：若含 issues 数组则取中间段；否则整段
        int i = reviewContent.indexOf("\"issues\"");
        if (i >= 0) {
            return reviewContent.substring(i);
        }
        return reviewContent;
    }

    private static String normalizeIssues(String s) {
        if (s == null) return "";
        // 去掉空白与 JSON/列表标点，只留语义字符，便于「parsed issues」与「raw JSON 片段」互比
        return s.replaceAll("[\\s\\-\\[\\]\\{\\}\"':,\\\\]+", "").toLowerCase();
    }

    private static boolean readBoolProperty(String key, boolean defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    private static int readIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 测试可见。 */
    int consecutiveReflections() { return consecutiveReflections; }
    boolean enabled() { return enabled; }

    /** /clear 或新会话时重置反螺旋计数。 */
    public void reset() {
        consecutiveReflections = 0;
    }
}
