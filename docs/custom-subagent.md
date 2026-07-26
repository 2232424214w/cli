# Custom Subagent

与 Multi-Agent（`/team` / `run_team`）**独立**。对齐：

- `restored-docs/1024-Custom-Subagent设计方案.md` 的 **CLI 全集**（RoleHub / belongPaas 除外）
- `restored-docs/1024-长任务下的SubAgent后台执行与通知机制.md` 的 **CLI 等价**（Redis / 大象推送除外）

## 对齐结论（定义与触发）

| 文档能力 | 状态 |
|----------|------|
| 三种触发（委托 / 路由 / `/subagent:name`） | ✅ |
| 定义 + SOUL/IDENTITY/MEMORY + skills/tools/model/timeout | ✅ |
| `from` 本地继承（对标 platform） | ✅ |
| 防递归 / 取消 / 超时 | ✅ |
| 可观测审计 + Webhook + `[via:]` | ✅ |
| 轻量 HA sessions/resume | ✅ |
| 脚手架 / show / delete / stats | ✅ |
| 内置 code-reviewer / researcher / sql-analyzer | ✅ |
| 微信硬指定（路由可开） | ✅ |
| RoleHub / belongPaas | ❌ 平台专属 |

可靠性要点：线程池任务显式 `CancellationContext.bind`（防旧 token 粘连）；`finish` 空消息保留 checkpoint；`ToolRegistry` 线程级 model 覆盖支持并行委托；`/subagent resume` 走 ESC 取消。

## 管理命令

`list` `reload` `status` `create` `templates` `show` `audit` `stats` `sessions` `resume` `delete`  
别名：`/sa-l` `/sa-st`

```text
/subagent create <name> [--template blank|code-reviewer|researcher|sql-analyzer] [--user|--project] [--force]
/subagent delete <name> --force [--user|--project]
/subagent:name 任务          # 消息前缀硬指定（非管理命令）
/subagent resume [sessionId]
```

## 配置

见 `.env.example`：`BETTERCLI_SUBAGENT_ROUTER_*`、`WEBHOOK_URL`、`SESSIONS_DIR`、`WECHAT_SUBAGENT_ROUTER`、`WECHAT_QUEUE_TIMEOUT_SECONDS`、`SUBAGENT_DEFAULT_MODE`、`SUBAGENT_BG_MAX_CONCURRENT`、`BG_REACT_PROVIDER`/`MODEL`。

## 长任务后台（1024）四期状态

| 期 | 内容 | 状态 |
|----|------|------|
| A | 微信按 conversationId FIFO 串行 + 排队回执 + 超时踢出 | ✅ |
| B | 真正后台模式 + 完成通知 + bg-react | ✅ |
| C | `running_agents_list` / `terminate_agent` / `steer_agent` | ✅ |
| D | 文档对齐矩阵 + 测试收口 | ✅ |

## 1024 长任务能力对齐矩阵

对照原文 `restored-docs/1024-长任务下的SubAgent后台执行与通知机制.md`（2026-05-05）逐条复核后的结果。

### 设计目标（§2）

| 目标 | 状态 | BetterCLI 做法 |
|------|------|----------------|
| 消除工具重复触发 | ✅ | 微信 FIFO：旧 ReAct 未结束时新消息只排队，不会并发开第二条推理（机制不同于「感知进行中工具状态」） |
| 消息处理顺序 | ✅ | `conversationId` 队列 + `finally` 等价：出队后再 `submit`，忙时不 drain |
| 长任务后台执行 | ✅ | `mode=background` + 完成通知 + bg-react |
| 避免无效 bg-react | ✅ | `sessionWrite ≤ lastBgReactStart` 跳过（内存，非 Redis） |

### 能力明细（§3–§9）

| 1024 能力 | BetterCLI 等价 | 状态 | 平台差异 / 说明 |
|-----------|----------------|------|-----------------|
| 同 conversationId FIFO 串行 | `ConversationMessageQueue` + `WechatMessageLoop` | ✅ | CLI 交互本身单线；微信通道实现队列 |
| 非队首「排队中」回执 | 微信推送排队文案 + 位次 | ✅ | 无大象卡片；轮到时 `startTyping`，无单独「思考中」卡片更新 |
| 超时从任意位置剔除 | `removeExpired` + `BETTERCLI_WECHAT_QUEUE_TIMEOUT_SECONDS`（默认 600） | ✅ | 单机内存，无 Redis TTL |
| 控制命令旁路入队 | `/stop` `/status` `/help` `/pause` `/resume` 等 `bypassQueue` | ✅ | 微信无 `/sa-st`；CLI 用 `/subagent status` |
| **终止思考不杀后台子 Agent** | `/stop` → `WechatAgentSession.cancel` → `cancelAllPending` | ⚠️ | **有意偏差**：当前会取消前台+后台委托并可能触发 cancelled 完成通知；1024 工作区只停主链路 |
| 前台 mode | 默认 / `mode=foreground` → `CUSTOM_SUBAGENT_PENDING:` + `materializeAsyncResults` | ✅ | 批次结束回填，非每 5s 轮询 |
| 后台 mode | `mode=background` / 微信 `setSubagentBackgroundDefault(true)` / `BETTERCLI_SUBAGENT_DEFAULT_MODE` | ✅ | CLI 默认仍前台（对标 API 同步） |
| bg-react 内再启子 Agent 走后台 | 微信会话级 `backgroundDefault=true` | ✅* | *CLI 若未设 DEFAULT_MODE=background，bg-react 内再委托仍可能前台 |
| 提前生成 subagentConversationId | `startAsync` 先分配 sessionId 再占位 | ✅ | |
| 完成后写通知 + 记写入时间 + 触发 bg-react | `onSubAgentBackgroundComplete` | ✅ | 顺序：history 追加 → `markSessionWrite` → `enqueue` |
| 完成通知格式（围栏 / Action / status） | `CustomSubAgentCompletionNotice` | ✅ | 前缀为 `BetterCLI runtime context`（对标 ReactMind）；多 `agent:` 字段 |
| 通知 role=user | `Message.user(notice)` | ✅ | |
| 通知不计入真实用户轮次 | `ConversationHistoryCompactor.isSyntheticUserTurn` | ✅ | 完成通知与 `[bg-react]` 不占 retain；极端仅合成消息时回退按全部 user 计 |
| bg-react 空/静默不推送 | `deliverBgReactReply` 跳过 blank / `OK` | ✅ | |
| session 写入时间去重 | `BgReactCoordinator` | ✅ | **内存 Map**；进程重启丢失；无 24h TTL |
| 仅 foreground 计入 pending | background 不进 materialize 等待 | ✅ | 对标 `launched && !background` |
| 子 Agent 结果队列隔离 | 独立 `PendingRun` / Future，不共享父 pending 表 | ✅ | |
| 运行中 Agent 注册表 | `LiveSubAgentRun` + `activeRuns` | ✅ | 进程内 Map；无 reactTraceId / Redis Hash |
| `running_agents_list` | `formatRunningTree`（depth / task / lastProgress / lastActiveMs） | ✅ | 文本树；仅主 Agent；子 Agent 工具白名单剥离 |
| `terminate_agent` 杀子树 | `terminateAgent` + 独立 `CancellationToken` | ✅ | 工具描述含「仅用户明确要求时」约束 |
| `steer_agent` 不落盘 | `AgentSteerService` + SubAgent 每轮 `drain` | ✅ | `[steer — ephemeral]` 仅附带当轮请求 |
| 微信放行三运行管理工具 | `WechatPolicyDecider` | ✅ | |
| Redis running Hash / reactTraceId | — | ❌ | 单机无需 |
| 大象 Rendezvous / backgroundSubagentCallback | — | ❌ | 微信 iLink `setBgReactReplyConsumer` 替代 |
| `/new` 后跳过 bg-react | `/clear` → 静默 `cancelAllPending(false)` + `sessionEpoch++` | ✅ | 不换 conversationId；epoch 使已排队 bg-react 失效（单进程等价） |
| 完成通知绑定会话世代 | `parentSessionEpoch` 写入事件；不匹配则丢弃 | ✅ | 堵 `/clear` 后迟到 finally 污染 |
| 每会话后台并发上限 | `BETTERCLI_SUBAGENT_BG_MAX_CONCURRENT`（默认 3） | ✅ | 超限拒绝并提示；0=不限制 |
| bg-react 独立小模型 | `BETTERCLI_BG_REACT_PROVIDER` / `_MODEL` | ✅ | 未配或失败回退主模型 |
| RoleHub / belongPaas | — | ❌ | 平台专属 |

### 已知限制（与 1024 §8 对应）

- 多子 Agent 陆续完成仍会多轮 bg-react（去重只跳「已覆盖写入」的重复入队）。
- 完成通知仍占 history 体积（不计 retain 轮次，但占 token）；极端堆积仍会触发压缩。
- `/stop` 仍会取消后台子 Agent（有意偏差，见矩阵）。

### 行为摘要

- **后台模式**：`run_subagent(..., mode=background)` 或 `BETTERCLI_SUBAGENT_DEFAULT_MODE=background`；微信通道默认 background。立即 `CUSTOM_SUBAGENT_BG_ACCEPTED:`，materialize 不等待；完成后写完成通知并触发 bg-react（写入时间去重）。
- **运行管理**（仅主 Agent）：`running_agents_list` 树形进度；`terminate_agent(conversation_id)` 杀子树；`steer_agent(conversation_id, message)` 下一轮纠偏（不落盘）。
- **刻意不做 / 有意偏差**：Redis 运行表、大象推送、与 `/team` 融合；`/stop` 会杀后台子 Agent（不同于 1024 终止思考）。

## 验证

```bash
mvn test -Dtest=CustomSubAgentRegistryTest,CustomSubAgentRunnerTest,CustomSubAgentMemoryToolTest,CustomSubAgentRouterTest,CustomSubAgentScaffoldTest,CustomSubAgentAuditTest,CustomSubAgentSessionStoreTest,CustomSubAgentBgReactTest,CustomSubAgentRuntimeMgmtTest,ConversationMessageQueueTest,WechatPolicyDeciderTest,CliCommandParserTest
```

## 实现

`com.bettercli.subagent.*`（含 `BgReactCoordinator` / `AgentSteerService` / `LiveSubAgentRun`）· `Agent` · `Main` / `WechatAgentSession` / `WechatMessageLoop` / `ConversationMessageQueue` / `WechatPolicyDecider`
