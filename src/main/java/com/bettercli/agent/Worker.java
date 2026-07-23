package com.bettercli.agent;

import java.io.PrintStream;

/**
 * Worker 抽象：本地 {@link SubAgent} 与远程 {@code com.bettercli.a2a.RemoteAgent} 的共同接口。
 *
 * <p>对标 2026 A2A + Claude Code agent teams 共识：worker 池应能混编本地与远程 agent。
 * 提取本接口使 {@code MixedWorkerPool} 可统一调度二者，为后续 {@code AgentOrchestrator}
 * worker 池从 {@code List<SubAgent>} 迁移到 {@code List<Worker>} 做准备。
 *
 * <p>方法签名与 {@link SubAgent} 现有公开方法对齐，SubAgent 无需改动方法体即可 implements。
 */
public interface Worker {
    String getName();
    AgentRole getRole();
    AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out);
    void clearHistory();
}
