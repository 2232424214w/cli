package com.bettercli.agent;

import java.util.List;

/**
 * 子代理结构化交接信封（2026 sub-agent as context-budget primitive 的落地）。
 *
 * 设计取舍（个人项目）：不要求 Worker LLM 自报 JSON 信封——LLM 自评不可靠且加延迟/成本，
 * 改为从运行轨迹确定性派生：artifacts 从 write_file/create_project 工具调用参数提取，
 * issues 从错误工具结果提取，confidence 从退出状态推导。这样信封可观测、可审计、不依赖
 * LLM 配合，Reviewer 拿到的是客观信号而非 Worker 的自述，降低自证偏差。
 *
 * Orchestrator 只消费 summary（给用户）+ 信封字段（给 Reviewer 打分），不接触 Worker 的
 * 探索过程（tool_call 噪声留在 Worker 的隔离 conversationHistory 里，turn 结束即蒸发）。
 *
 * @param agentName        产出此信封的子代理名
 * @param role             角色
 * @param summary          给用户/上游的摘要（Worker 最终 content）
 * @param artifacts        本次运行触发改动的文件/项目（从工具调用参数派生）
 * @param issues           观察到的问题（错误工具结果 / 异常）
 * @param confidence       置信度 [0,1]：正常完成 0.85 / 预算耗尽 0.5 / 出错 0.2（不设 1.0，避免自证偏差）
 * @param exhaustedBudget  是否因 token/步数预算耗尽而退出（"stop when done 不是停止条件"）
 * @param stepCount        LLM 迭代轮数
 * @param inputTokens      累计输入 token
 * @param outputTokens     累计输出 token
 * @param error            是否以错误结束
 * @param errorMessage     错误信息（error=false 时为 null）
 */
public record SubAgentResult(String agentName, AgentRole role, String summary,
                             List<String> artifacts, List<String> issues, double confidence,
                             boolean exhaustedBudget, int stepCount,
                             long inputTokens, long outputTokens,
                             boolean error, String errorMessage) {

    /**
     * 是否需要对抗式核实：低置信度或产出了 artifacts（涉及文件改动，高风险）。
     * Reviewer 据此决定是否必须实际 read_file 核实，而非只看 Worker 自述。
     */
    public boolean needsAdversarialVerification() {
        return confidence < 0.7 || (artifacts != null && !artifacts.isEmpty());
    }

    /**
     * 给用户/日志打印的一行信封摘要。
     */
    public String oneLineSummary() {
        return "📦 [" + agentName + "] 交接: artifacts=" + (artifacts == null ? 0 : artifacts.size())
                + ", issues=" + (issues == null ? 0 : issues.size())
                + ", confidence=" + String.format("%.2f", confidence)
                + ", steps=" + stepCount
                + (exhaustedBudget ? ", 预算耗尽" : "")
                + (error ? ", 错误" : "");
    }
}
