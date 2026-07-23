# Multi-Agent 子系统设计

> 本文记录 BetterCLI `/team` 模式（`AgentOrchestrator`）的设计决策与权衡，供协作与面试复盘。
> 对标参考系：AutoGen（角色 + 多模型）、MetaGPT（角色专业化 + SOP）、ChatDev（程序员/测试员分工）。
> 代码入口：`com.bettercli.agent.AgentOrchestrator` / `SubAgent` / `AgentRole` / `RoleModelResolver`。

## 1. 要解决的问题

最初的 Multi-Agent 实现是"伪 Multi-Agent"：

- 三个角色（Planner / Worker / Reviewer）名义上分工，但 **能力没隔离**——REVIEWER 一个工具都没有，却要审查代码，审查环节形同虚设。
- 所有角色共用同一个 `LlmClient`，**成本和效果没分化**——Planner 只拆任务却用和 Reviewer 一样的贵模型。
- 2 个 Worker 完全同构，`BlockingQueue` 谁抢到谁干，**跟单 Worker 并行调工具没区别**。
- 每步 `clearHistory()`，**Worker 不记得自己干过什么**，重复读取同一文件。

面试官追问"你的 Multi-Agent 跟单 Agent 并行调工具有什么本质区别"时，这些问题答不上来。

## 2. 四阶段迭代

### 阶段 A：角色工具白名单

**决策**：把 `SubAgent.shouldUseTools()` 的二元布尔（只 WORKER=true）升级成按角色的工具白名单 `AgentRole.allowedTools()`。

| 角色 | 白名单 | 理由 |
|------|--------|------|
| PLANNER | `read_file`/`glob_files`/`grep_code`/`list_dir`/`web_search`/`web_fetch` | 规划前先核实代码/查证 API，不能凭空规划；但不能写/执行/改记忆 |
| REVIEWER | `read_file`/`glob_files`/`grep_code`/`list_dir` | 审查必须实际看代码，不能只凭执行者自述；不联网避免被外部信息带偏，不写不执行避免误改代码 |
| WORKER | `null`（不限制） | 执行者要能动手，全量内置 + MCP |

**两处生效**：
1. `ToolRegistry.getToolDefinitions(whitelist)`——只把白名单内工具 schema 下发给 LLM，从源头减少幻觉。
2. `ToolRegistry.executeTools(invocations, whitelist)`——执行层拦截越权调用（含 `mcp__*`），防御 LLM 幻觉出白名单外工具名。

**权衡**：白名单用 `Set<String>` 而非能力位图，简单可读；MCP 动态工具对非 WORKER 一律不暴露（更保守）。`null` 表示"不限制"而非"无工具"，避免 WORKER 漏配新增工具。

### 阶段 B：角色级模型分配

**决策**：`RoleModelResolver implements Function<AgentRole, LlmClient>`，按角色解析不同模型。

- Planner → 便宜快模型（只拆任务）
- Reviewer → 强推理模型（要判断质量）
- Worker → 用户主模型

**配置**：`-Dbettercli.team.<role>.provider` 或 `BETTERCLI_TEAM_<ROLE>_PROVIDER`，未配或建不出来时回退主模型（向后兼容）。

**权衡**：所有角色仍共享同一个 `ToolRegistry`（审计日志、快照服务、MCP 状态共享），只换 LLM 客户端。`setRoleClientResolver` 会重建 SubAgent 并重新下发已设置的 Skill 系统与外部上下文，避免 setter 顺序依赖 bug。

### 阶段 C：Worker 分工 + 指派路由 + 持久记忆

**决策 1（专长分化）**：`setWorkerSpecialties(List<String>)` 给 worker-1/worker-2 注入差异化专长，默认按能力维度（实现 vs 分析/验证），通过 `{{workerSpecialty}}` 注入 `team-worker.md`。

**决策 2（指派路由）**：`ExecutionStep` 新增 `assignee` 字段，`parsePlan` 读取规划者指定的 worker 名，`normalizeAssignee` 过滤幻觉出的不存在 worker 名（回退默认调度）。串行 `pickWorker` / 并行 `takeWorker` 两条路径都按 assignee 路由，命中时打印 🎯。

**决策 3（持久记忆）**：Worker 不再每步 `clearHistory()`，保留跨步骤对话记忆；超 window 时由 `SubAgent.maybeCompactHistory` 自动压缩早期消息。`team-worker.md` 声明持久记忆并要求避免重复读取。

**权衡**：
- 持久记忆的代价是跨不相关步骤的轻度上下文污染。但 Multi-Agent 的价值正是"专家记住自己的工作线程"，这个代价可接受，且由压缩兜底。
- `takeWorker` 在 assignee 被同批次其他步骤占用时回退到任意空闲 worker，保证并行度不因指派冲突而退化。
- 团队名单通过 `{{teamWorkers}}` 注入 `team-planner.md`，让规划者知道有哪些 Worker 及专长，从而合理指派。

### 阶段 D：Ablation + 文档

**决策**：建可运行的 benchmark 框架 + 方法论文档，用数据证明"真 Multi-Agent"在复杂任务上的价值。详见 `docs/multi-agent-ablation.md`。

### 阶段 E：动态重规划 + Workflow LLM 节点（迭代路线阶段 1）

**决策 1（动态重规划）**：`AgentOrchestrator` 在 step `FAILED`（Worker 出错/空结果）或 `EXHAUSTED`（审查重试耗尽）时调用 planner 重新规划。保留 `COMPLETED` 步骤，丢弃失败锚点与剩余 PENDING，新步骤 id 加 `r<n>_` 前缀。`MAX_REPLAN_PER_RUN=2` 防风暴。Planner 对话历史在整个 `run()` 内保留供 replan 复用，结束时统一 `clearHistory`。

**决策 2（Workflow LLM 胶水）**：新增 `WorkflowAdapters`，把 `Worker#executeWithContext` 包成 `TaskStep.action`（`subAgentAction` / `llmTask`），以及 fan-in 汇总（`fanInAction` / `fanInTask`：读黑板多 artifact → 一次 LLM 合成）。`WorkflowRuntime` 本身仍不感知 LLM，胶水层可独立单测。

**权衡**：replan 触发条件克制（仅 FAILED/EXHAUSTED，不在每次审查拒绝时立刻 replan），避免 token 爆炸；EXHAUSTED 路径仍 `withResult` 保留结果以兼容无 replan 时的旧行为，但 merge 时排除失败锚点 id。

### 阶段 F：Durable checkpoint + 完成回推（迭代路线阶段 3）

**决策**：`DurableWorkflowBridge` 将 durable taskId 与 `WorkflowCheckpointStore` 对齐；`WorkflowRuntime` 支持 skip + checkpoint listener；`TaskCompletionListener` 让微信/HTTP 作为终态回推插件。Side-Git 通过可选 `snapshotHook` 接入，不强制依赖快照系统启用。

## 3. 架构约束

- **共享层**：`ToolRegistry` / `MemoryManager` / `AuditLog` / `SnapshotService` / MCP 状态由三条主路径共享，Multi-Agent 不另起一套。
- **隔离层**：每个 SubAgent 有独立 `conversationHistory`、独立 `AgentBudget`、独立角色 prompt、（可选）独立 LlmClient。
- **并行安全**：并行批次每步用独立 `ByteArrayOutputStream` 缓冲流式输出，完成后按 step_id 顺序 flush，避免多线程写同一终端流交错；并行路径每步创建独立 Reviewer 实例，避免对话历史竞争。
- **取消传播**：`CancellationContext.isCancelled()` 在循环/步骤入口检查，用户取消后优雅退出。

## 4. 已知边界

- 角色间记忆仍共享 `MemoryManager`（项目级长期记忆），未做角色级记忆命名空间隔离（任务书 §3.6 列为可观察优化项）。
- `assignee` 路由依赖规划者输出合法 worker 名；`normalizeAssignee` 兜底幻觉，但不保证规划者一定指派。
- Ablation 数据需真实 LLM 调用，benchmark 框架默认 `@Disabled`，需配置 API Key 后手动运行。
