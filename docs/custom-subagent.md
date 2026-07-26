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

见 `.env.example`：`BETTERCLI_SUBAGENT_ROUTER_*`、`WEBHOOK_URL`、`SESSIONS_DIR`、`WECHAT_SUBAGENT_ROUTER`、`WECHAT_QUEUE_TIMEOUT_SECONDS`、`SUBAGENT_DEFAULT_MODE`。

## 长任务后台（1024）四期状态

| 期 | 内容 | 状态 |
|----|------|------|
| A | 微信按 conversationId FIFO 串行 + 排队回执 + 超时踢出 | ✅ |
| B | 真正后台模式 + 完成通知 + bg-react | ✅ |
| C | `running_agents_list` / `terminate_agent` / `steer_agent` | ✅ |
| D | 文档对齐矩阵 + 测试收口 | ✅ |

## 1024 长任务能力对齐矩阵

| 1024 能力 | BetterCLI 等价 | 状态 | 平台差异 / 说明 |
|-----------|----------------|------|-----------------|
| 同 conversationId FIFO 串行 | `ConversationMessageQueue` + `WechatMessageLoop` | ✅ | CLI 交互本身单线；微信通道实现队列 |
| 非队首「排队中」回执 | 微信推送排队文案 | ✅ | 无大象卡片，纯文本回执 |
| 超时从任意位置 `removeIf` | `evictExpired` + `BETTERCLI_WECHAT_QUEUE_TIMEOUT_SECONDS`（默认 600） | ✅ | 单机内存，无 Redis TTL |
| `/stop` `/status` 等旁路入队 | 控制命令入队前处理 | ✅ | 对标终止思考 / `/sa-st` 旁路 |
| 前台 mode（同步等结果） | `run_subagent` 默认 / `mode=foreground` → `CUSTOM_SUBAGENT_PENDING:` + materialize | ✅ | 批次结束回填，非每 5s 轮询 API |
| 后台 mode（accepted 即结束） | `mode=background` / 微信默认 background / `BETTERCLI_SUBAGENT_DEFAULT_MODE` | ✅ | 对标大象工作区；CLI 默认仍前台 |
| 完成后写入 session 通知 | `CustomSubAgentCompletionNotice` 注入主会话（role=user） | ✅ | 前缀 `BetterCLI runtime context`（对标 ReactMind） |
| untrusted 结果围栏 | `<<<BEGIN/END_UNTRUSTED_CHILD_RESULT>>>` | ✅ | 与 1024 格式一致 |
| status done / cancelled | `✅ done` / `❌ cancelled` | ✅ | |
| bg-react 拉起主 Agent 汇总 | `BgReactCoordinator` + `Agent` 空闲后推理 | ✅ | 微信经 `setBgReactReplyConsumer` 回推 |
| session 写入时间去重 | `markSessionWrite` vs `lastBgReactStart` | ✅ | **内存 Map**，非 Redis；同进程有效 |
| 仅 foreground 计入 pending | background 不走 materialize 等待 | ✅ | 对标 `DispatchResult(launched, background)` |
| 子 Agent 结果队列隔离 | 子 run 独立 session / Future，不共享 pending 表 | ✅ | 无 BusinessInfo.deepCopy 历史坑 |
| 运行中 Agent 注册表 | `LiveSubAgentRun` + `activeRuns` | ✅ | **进程内** ConcurrentHashMap，非 Redis Hash |
| `running_agents_list` 树形进度 | `formatRunningTree`（progress / lastActiveTime） | ✅ | 仅主 Agent 工具；子 Agent 白名单剥离 |
| `terminate_agent` 杀子树 | `terminateAgent(conversationId)` + 每 run 独立 `CancellationToken` | ✅ | `/cancel` 仍可取消当前主任务 |
| `steer_agent` 下一轮纠偏 | `AgentSteerService` 内存队列，不落盘 | ✅ | 工具结束后下一轮 LLM 注入 |
| 微信放行三运行管理工具 | `WechatPolicyDecider` | ✅ | 与 `run_subagent` 同级只读/管控类 |
| Redis running Hash / reactTraceId | — | ❌ | 单机 CLI 无需跨请求 Redis 协调 |
| 大象 Rendezvous / backgroundSubagentCallback | — | ❌ | 微信 iLink 本机回推替代 |
| `/new` 清 running key 跳过 bg-react | — | ⚠️ | CLI `/clear` 清主会话；跨进程 HA 不保证跳过已排队 bg-react |
| RoleHub / belongPaas | — | ❌ | 平台专属 |

### 行为摘要

- **后台模式**：`run_subagent(..., mode=background)` 或 `BETTERCLI_SUBAGENT_DEFAULT_MODE=background`；微信通道默认 background。立即 `CUSTOM_SUBAGENT_BG_ACCEPTED:`，materialize 不等待；完成后写完成通知并触发 bg-react（写入时间去重）。
- **运行管理**（仅主 Agent）：`running_agents_list` 树形进度；`terminate_agent(conversation_id)` 杀子树；`steer_agent(conversation_id, message)` 下一轮纠偏（不落盘）。
- **刻意不做**：Redis 运行注册表、大象推送协议、与 `/team` 融合。

## 验证

```bash
mvn test -Dtest=CustomSubAgentRegistryTest,CustomSubAgentRunnerTest,CustomSubAgentMemoryToolTest,CustomSubAgentRouterTest,CustomSubAgentScaffoldTest,CustomSubAgentAuditTest,CustomSubAgentSessionStoreTest,CustomSubAgentBgReactTest,CustomSubAgentRuntimeMgmtTest,ConversationMessageQueueTest,WechatPolicyDeciderTest,CliCommandParserTest
```

## 实现

`com.bettercli.subagent.*`（含 `BgReactCoordinator` / `AgentSteerService` / `LiveSubAgentRun`）· `Agent` · `Main` / `WechatAgentSession` / `WechatMessageLoop` / `ConversationMessageQueue` / `WechatPolicyDecider`
