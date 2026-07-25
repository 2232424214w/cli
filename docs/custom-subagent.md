# Custom Subagent

与 Multi-Agent（`/team` / `run_team`）**独立**。对齐 `restored-docs/1024-Custom-Subagent设计方案.md` 的 **CLI 全集**（RoleHub / belongPaas 除外）。

## 对齐结论

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

## 长任务后台（1024 后续）

| 期 | 内容 | 状态 |
|----|------|------|
| A | 微信按 conversationId FIFO 串行 + 排队回执 + 超时踢出 | ✅ |
| B | 真正后台模式 + 完成通知 + bg-react | ✅ |
| C | running_agents_list / terminate_agent / steer_agent | 待做 |
| D | 文档矩阵 + 测试收口 | 待做 |

后台模式：`run_subagent(..., mode=background)` 或 `BETTERCLI_SUBAGENT_DEFAULT_MODE=background`；微信通道默认 background。完成后写入完成通知并触发 bg-react（写入时间去重）。

## 实现

`com.bettercli.subagent.*` · `Main` / `WechatAgentSession` / `ConversationMessageQueue`
