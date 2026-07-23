# 迭代规划：多 LLM 协作深化 → 异步跨端 Agent 运行时

> 本文件是 BetterCLI 的**演进路线规划文档**，不是行为约定（行为约定以 `AGENTS.md` 为准）。
> 任何新会话/Agent 进入仓库，应把本文件作为"未来迭代方向"的权威来源，避免在对话被压缩后丢失规划。
> 状态变化时更新本文件，并在 `AGENTS.md` 的"给新线程的导航"表保持登记。

## 一、背景与定位决策

### 1.1 项目性质

BetterCLI 是**个人求职作品集项目**（非商业产品）。衡量标准不是 PMF/收入/份额，而是：

- **工程深度可展示**：面试官能就某个点深聊 20 分钟
- **技术叙事差异化**：和其他候选人（95% 在写 Claude Code 翻版）拉开记忆点
- **可演示 + 可逐点交付**：每阶段独立可 demo，不会做不完

### 1.2 当前定位的问题

BetterCLI 当前对外叙事是"对标 Claude Code 的 Java Agent CLI"。但 2026 年中这个赛道已是 9 家主力 + 12 个开源团队的红海，Claude Code 被业内定义为"品类模板，所有人都在 clone"。**Java 在"开发者终端副驾"场景是纯劣势**——作为第 N 个 harness 没有胜算。

### 1.3 确认的主线方向

**方向 A：异步跨端 Agent 运行时** —— 不是"坐在终端前的同步编码副驾"，而是"可以远程下达、后台持久执行、跨端收回结果的 agent 运行时"。

差异化逻辑：
- 不跟 Claude Code/Codex CLI 卷"终端同步副驾"
- 不跟微信官方"小薇"卷"个人生活助手 in 小程序"（小薇 2026.6 灰度，是 single-user 生活助手，不碰代码库/开发机/长任务）
- 切"**远程指挥我的开发机/服务器/代码库干活**"这个缝
- Java 在"服务端长跑运行时"反劣为优（并发、JGit/Side-Git、SQLite、HttpServer 是后端硬功夫）

### 1.4 核心痛点（驱动本轮规划的直接原因）

**多 LLM 协作太薄**。整个 `agent/` + `plan/` 包只有 4 个 `llmClient.chat()` 调用点（`Agent#run`、`SubAgent#execute`、`PlanExecuteAgent`、`Planner#createPlan`）。所谓"Multi-Agent 协作"本质是"多个串行 ReAct 的接力赛 + 同批次并行"，不是"多 LLM 实时协作网络"。业界成熟的 fan-out/fan-in 调研、反思循环收敛、动态重规划、图状编排、多路径投票，**一个都没有**。

## 二、代码现状盘点（决定迭代成本的 3 个关键发现）

### 发现 1：`Planner.replan` 写好了但没人调用

`plan/Planner.java#replan(ExecutionPlan, String)` 方法完整可用——保留原 goal，把已完成任务 + 失败原因作 extraContext 重新规划。

但 `agent/AgentOrchestrator.java#run` 里 planner 出完计划就 `planner.clearHistory()` 退场，执行阶段再也不参与。**动态重规划方向几乎零成本起步**——方法已写好，只差在 `runStep` 失败/审查拒绝时回调接线。

### 发现 2：`WorkflowRuntime` 是"脚本驱动不调 LLM"的半成品，和 Multi-Agent 割裂

`agent/WorkflowRuntime.java#executeTask` 里 `TaskStep.action` 是 `Function<SharedState,String>` 纯函数，runtime 只调 `t.action().apply(state)`。控制流（顺序/并行/条件/循环）完整，且可独立单测不依赖 LLM。

源码注释自己写了"实际使用时把 `SubAgent.execute` 包成 action 注入"——**但这个胶水没写**。Workflow 和 Multi-Agent 是两套独立东西。这是把"串行接力"升级成"图状 LLM 协作"的关键缺口。

### 发现 3：`ReflectionService` 已有单 agent 轻量反思，但天花板低

`agent/ReflectionService.java`：工具失败后构造反思提示注入下一轮 ReAct，**不额外调 LLM**（零成本增量），靠 `consecutiveReflections` 计数器反螺旋。仅 ReAct 主 Agent 启用。

它印证了痛点：现有反思是"顺便在下一轮里想想"，而深度协作需要"专门调 LLM 做反思/辩论"。它是好地基，但需要扩展出"多 agent 专门调 LLM"路径。

## 三、候选迭代方向评估

5 分制，按代码就绪度加权：

| 方向 | 代码就绪度 | 工作量 | 求职记忆点 | 与主线A协同 | 风险 | 加权 |
|------|:---:|:---:|:---:|:---:|:---:|:---:|
| 动态重规划接线 | 5（replan 已写好） | S | 4 | 4 | 低 | **最高** |
| Workflow 打通 LLM 节点 | 4（runtime 完整，差胶水） | M | 5 | 4 | 中 | **高** |
| Fan-out/Fan-in 调研 | 3（ParallelStep 有并行，差汇总语义） | M | 5 | 4 | 中 | 高 |
| 反思循环收敛升级 | 3（ReflectionService 有地基） | M | 4 | 3 | 中 | 中高 |
| DurableTask×Multi-Agent | 2（两者割裂，要补 checkpoint） | L | 5 | 5 | 中高 | 中 |
| 多路径探索+投票 | 1（无基础） | M | 3 | 2 | 中 | 低 |

**结论**：前两项是"已有代码只差接线/胶水"，性价比碾压。它们恰好解决"多 LLM 协作太薄"痛点——把 planner 从"一次性静态 DAG"升级成"执行中动态调整 + 图状 LLM 编排"。

## 四、确认的 3 阶段迭代路线

按代码就绪度排序，每阶段独立可交付、可面试讲。从"深化多 LLM 协作"（痛点）平滑过渡到"异步跨端运行时"（主线）。

### 阶段 1 [已完成]：动态重规划 + Workflow 打通 LLM 节点

**目标**：把"串行接力 + 一次性静态 DAG"升级成"执行中可动态调整的图状 LLM 协作"。

**已交付**：
1. **`AgentOrchestrator` 动态重规划**：`StepOutcome`（COMPLETED/FAILED/EXHAUSTED）+ `triggerReplan`；`MAX_REPLAN_PER_RUN=2`；测试 `ReplanIntegrationTest`。
2. **`WorkflowAdapters`**：`subAgentAction` / `fanInAction` / `llmTask` / `fanInTask`；测试 `WorkflowLlmNodeTest`。
3. 文档：`AGENTS.md` + `docs/multi-agent-design.md` 阶段 E。

**面试叙事**：「planner 不是一次性出静态 DAG 就退场——执行中基于 worker 失败和审查反馈动态重规划；Workflow 节点通过 WorkflowAdapters 真正调 LLM，fan-in 汇总多路产物。」

**工作量**：S~M。**风险**：低。

### 阶段 2 [已完成]：Fan-out/Fan-in 调研 + 反思循环收敛

**目标**：把"多 LLM 协作"从"接力"升级成"并行探索 + 辩论收敛"。

**已交付**：
1. **`ScatterGather`**：同一目标 N 路角度并行调研（`ParallelStep`）+ `fanInTask` 合成；测试 `ScatterGatherTest`。
2. **增量辩论收敛**：`ReflectionService.buildIncrementalDebateContext` / `isDebateConverged`；`AgentOrchestrator.runStep` 用增量修改替代推倒重来，issues 实质相同或显式 `converged` 时停止辩论；测试 `DebateConvergenceIntegrationTest`。

**面试叙事**：「支持 scatter-gather 并行调研和多轮反思收敛——为同一问题并行探索多角度再合成，worker 和 reviewer 增量辩论到收敛而非盲目硬重试。」

**工作量**：M。**风险**：中。

### 阶段 3 [已完成]：DurableTask × Multi-Agent + checkpoint 断点续跑

**目标**：把多 agent 图状协作套上 durable 执行，成为异步跨端运行时。

**已交付**：
1. **`WorkflowCheckpoint` / `WorkflowCheckpointStore`**：每步持久化 executed ids + 黑板 artifacts（+ 可选 Side-Git snapshotId）。
2. **`WorkflowRuntime` 断点续跑**：`withSkippedSteps` + `setCheckpointListener`；已完成 TaskStep 跳过。
3. **`DurableWorkflowBridge`**：`TaskRunner` 实现，taskId = checkpoint runId；崩溃重入队后自动恢复黑板并续跑。
4. **`TaskCompletionListener`**：`DurableTaskManager` 终态主动回推钩子（微信/HTTP 可插拔）。
5. **`TaskRunner.run(taskId, prompt)`** 默认方法，向后兼容。
6. 测试：`DurableWorkflowResumeTest`。

**面试叙事**：「多 agent 图状协作跑在 durable runtime 上，每步 checkpoint，崩溃从断点续跑；任务完成通过 CompletionListener 主动回推——异步跨端 agent 运行时，不是盯着终端的同步副驾。」

**工作量**：L。**风险**：中高（已落地 MVP：文件 checkpoint + 步骤跳过；Side-Git 通过可选 snapshotHook 接入）。

## 五、阶段 1 具体落地（立刻可做）

### 5.1 `AgentOrchestrator` 接线 `Planner.replan`（核心改动）

- `runStep` 重试 while 循环耗尽 `MAX_RETRIES_PER_STEP` 后：不再直接 `withResult(acceptedResult)` 放弃，而是收集失败上下文 → 调 `planner.replan(currentPlan, failureReason)` → 用新计划的未完成步骤替换当前 `steps` 里该 step 之后的所有 PENDING → 重新进入主 `while(getExecutableSteps)` 循环。
- 加 `MAX_REPLAN_PER_RUN`（如 2）防 replan 风暴。
- planner 不能再 `clearHistory()` 退场——保留供 replan 复用（或 replan 时重建 planner 上下文）。

### 5.2 Workflow↔SubAgent 胶水（新增适配类）

- 新增 `agent/WorkflowAdapters.java`：
  - `subAgentAction(SubAgent, AgentMessage, context)` → `Function<SharedState,String>`，内部调 `SubAgent#executeWithContext`，结果写黑板。
  - `fanInAction(SubAgent, List<String> artifactKeys)`：读黑板多个 artifact → 拼成 prompt → 一次 LLM 调用合成。

### 5.3 测试（遵循 AGENTS.md 验证路径约定）

- `ReplanIntegrationTest`：构造必失败 step，断言触发 replan 且新计划跳过已完成 step。
- `WorkflowLlmNodeTest`：用 mock LLM 验证 workflow 节点真正调了 LLM 且产物入黑板。

### 5.4 文档联动（AGENTS.md 硬规则 1/4）

- 更新 `AGENTS.md` Multi-Agent 段：声明动态重规划 + workflow LLM 节点。
- 更新 `docs/multi-agent-design.md`。
- 在 `AGENTS.md` 验证路径表加 `ReplanIntegrationTest` / `WorkflowLlmNodeTest`。

## 六、与 AGENTS.md 的联动约定

- 本文件是**规划**，`AGENTS.md` 是**行为约定**。阶段落地后，已交付的行为同步进 `AGENTS.md`，本文件只保留"未来要做的"和"已完成阶段的索引"。
- 每完成一个阶段：在对应阶段标题加 `[已完成 v?.?.?]` 标记，并把交付行为摘要补进 `AGENTS.md`。
- 本文件登记在 `AGENTS.md` "给新线程的导航" 表，确保上下文压缩后新会话仍能找到。
