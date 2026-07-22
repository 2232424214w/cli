# Multi-Agent Ablation 方法论

> 本文记录如何用数据验证 BetterCLI Multi-Agent 的价值，供面试复盘与持续回归。
> 配套代码：`src/test/java/com/bettercli/agent/TeamBenchmark.java`（默认 `@Disabled`，需 API Key）。

## 1. 实验目标

回答一个核心问题：**Multi-Agent 相比单 Agent，在什么任务复杂度下值得？**

假设：
- 简单任务（1-2 步）：单 Agent 更省 token、更快（Multi-Agent 的规划/审查开销是纯成本）。
- 复杂任务（3+ 步、多文件/多类型）：Multi-Agent 的角色分工 + 持久记忆 + 指派路由带来更高的成功率，token 开销被质量补偿。

## 2. 三组对照

| 模式 | 配置 | 代表 |
|------|------|------|
| A. Single | `Agent.run`（ReAct） | 单 Agent 基线 |
| B. Multi (baseline) | `AgentOrchestrator`，不设角色模型、不设专长（所有角色共用主模型、Worker 无专长） | "伪 Multi-Agent"——角色工具白名单仍生效（阶段 A 是基础），但无模型/专长/指派分化 |
| C. Multi (full) | `AgentOrchestrator` + `RoleModelResolver`（Planner 用便宜模型、Reviewer 用强模型）+ `setWorkerSpecialties` + assignee 路由 + 持久记忆 | "真 Multi-Agent"——阶段 A/B/C 全开 |

> 注：阶段 A 的角色工具白名单在 B/C 都生效，是基础能力而非对照变量。对照变量是 B vs C（模型分配 + 专长 + 指派 + 记忆）。

## 3. 度量指标

每个任务、每个模式记录：

| 指标 | 来源 | 含义 |
|------|------|------|
| `inputTokens` / `outputTokens` | `AgentBudget` / `ChatResponse` | LLM 调用累计 token |
| `elapsedMs` | `System.nanoTime` | 端到端墙钟耗时 |
| `success` | 结果是否含错误标记（`❌`/`⚠️`/异常） | 任务是否成功 |
| `reviewerRejections` | `AgentOrchestrator` 重试计数 | Reviewer 拒绝次数（仅 Multi 模式） |
| `llmCalls` | 迭代计数 | LLM 调用次数 |

汇总：每模式 × 每任务复杂度桶（simple/medium/complex）取均值。

## 4. 任务集

固定 5-10 个任务，按复杂度分桶（避免 cherry-pick）：

- **simple**（1-2 步）："读取 README.md 并总结"、"统计 src 下 Java 文件数"
- **medium**（3-4 步，单文件多改）："给 X 类加一个 Y 方法并补测试"
- **complex**（5+ 步，多文件/多类型）："给 CLI 加一个 /foo 斜杠命令，含解析、注册、测试、文档"

任务集写在 `TeamBenchmark.TASKS`，可重复运行。

## 5. 如何运行

```bash
# 需要至少一个 API Key（GLM_API_KEY 等）已配置
mvn -DskipTests=false -Dtest=TeamBenchmark -Dbettercli.benchmark.enabled=true test
# 或直接跑 main：
mvn -q exec:java -Dexec.mainClass=com.bettercli.agent.TeamBenchmark
```

输出：`docs/multi-agent-ablation-results.md`（自动生成，含结果表格）。

## 6. 结果模板

> 下表由 `TeamBenchmark` 自动填充。未运行前为模板，运行后替换为真实数据。

| 任务 | 复杂度 | 模式 | inputTok | outputTok | elapsedMs | success | reviewerReject | llmCalls |
|------|--------|------|---------|----------|-----------|---------|---------------|---------|
| _待填_ | simple | A Single | | | | | | |
| _待填_ | simple | B Multi-base | | | | | | |
| _待填_ | simple | C Multi-full | | | | | | |
| _待填_ | complex | A Single | | | | | | |
| _待填_ | complex | B Multi-base | | | | | | |
| _待填_ | complex | C Multi-full | | | | | | |

## 7. 预期解读框架

- 若 **complex 桶 C 的成功率显著高于 A/B**，且 token 开销增幅 < 成功率增幅 → Multi-Agent 价值成立。
- 若 **simple 桶 C 的 token 开销显著高于 A 且成功率无差异** → Multi-Agent 应只在复杂度阈值之上启用（可作为后续 `/team` 自动门控的依据）。
- 若 **B vs C 无显著差异** → 模型分配/专长/指派的增益不显著，需重新审视阶段 B/C 的设计。

**重要**：结论由数据决定，不由假设决定。若数据否定假设，方案就改成"Multi-Agent 仅在 N 步以上任务启用"——这本身是有效结论。

## 8. 已知局限

- 真实 LLM 调用有随机性，单次运行不构成统计意义；建议每任务每模式跑 3 次取中位数。
- token/latency 受网络与 provider 负载影响，跨时间对比需固定 provider 与时段。
- "success" 用启发式判定（错误标记），复杂任务的部分成功可能误判，需人工抽检。
