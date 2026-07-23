package com.bettercli.agent;

import java.util.List;
import java.util.function.Function;

/**
 * Workflow 脚本的步骤类型（sealed，对标 Claude Code Dynamic Workflow 的控制流原语）。
 *
 * <ul>
 *   <li>{@link TaskStep}      顺序任务：执行 action，产物写黑板</li>
 *   <li>{@link ParallelStep}  并行：所有分支同时执行（fan-out）</li>
 *   <li>{@link ConditionalStep} 条件：求值黑板 condition，执行 then 或 else 分支</li>
 *   <li>{@link LoopStep}      循环：重复执行 body 直到 condition 满足或 maxIterations</li>
 * </ul>
 */
public sealed interface WorkflowStep permits TaskStep, ParallelStep, ConditionalStep, LoopStep {
    String id();
}
