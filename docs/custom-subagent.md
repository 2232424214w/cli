# Custom Subagent

与 Multi-Agent（`/team` / `run_team` 固定 Planner + Worker×2 + Reviewer）**独立**的可定义子 Agent 能力。

对齐文档：`restored-docs/1024-Custom-Subagent设计方案.md`（CLI 子集）。

## 与 1024 文档对齐矩阵

| 文档能力 | BetterCLI | 说明 |
|----------|-----------|------|
| 独立 prompt / 工具 / 模型 / maxTurns / skills / MEMORY | ✅ | `AGENT.md` + sidecar |
| 方式一：`run_subagent` 委托 + 并发 + 隔离历史 | ✅ | 异步占位 + 批次回填 |
| 方式二：路由 LLM 跳过主 Agent | ✅ | 置信度门控 + sticky + 专用模型可配 |
| 方式三：`/subagent:name` / `/sa:name` 硬指定 | ✅ | **消息前缀**；空格 `/subagent name task` 仍禁止（管理命名空间） |
| 未找到时列出可用 | ✅ | |
| `@main` / `/main` 强制主 Agent | ✅ | |
| 禁止递归 / 停止联动 / 超时 | ✅ | |
| 作用域覆盖 | ✅ | builtin &lt; user &lt; project（对标 PaaS/用户） |
| `/sa-l` `/sa-st` | ✅ | list / status 别名 |
| 审计 `subagent_name` / `parent_conversation_id` | ✅ | JSONL + `/subagent audit` |
| Webhook `SUBAGENT_*` | ✅ 轻量 | `BETTERCLI_SUBAGENT_WEBHOOK_URL`，5s fail-open |
| 脚手架 | ✅ | `/subagent create` / `templates` |
| 微信通道 | ✅ | 硬指定始终可用；路由默认关（`BETTERCLI_WECHAT_SUBAGENT_ROUTER=true` 开） |
| RoleHub / platform 类型 / belongPaas | ❌ | 平台专属 |
| 进程崩溃 HA 续跑 | ❌ | CLI 不做；可用审计回溯 |

## 触发原则（入站优先级 = 文档 §4.1）

1. `/subagent:name …` 或 `/sa:name …` → 硬指定直达  
2. 路由 LLM（可关 / 可配专用模型）→ 命中则直达  
3. 主 Agent（可再 `run_subagent`）

| 方式 | 是否支持 | 说明 |
|------|----------|------|
| 主 Agent 语义委托 `run_subagent` | 是 | 异步占位 → 批次结束回填；状态栏 `sa:name` / `sa×N` |
| 轻量路由 LLM | 是 | 置信度默认 ≥0.70；sticky；fail-open |
| 消息前缀硬指定 | 是 | `/subagent:name` / `/sa:name` |
| `/subagent <name> <task>` 空格硬指定 | **否** | 与管理命令冲突，未知命令 |
| 管理命令 | 是 | list / reload / status / create / templates / audit；`/sa-l` `/sa-st` |

配置：

- `bettercli.subagent.router.enabled` / `BETTERCLI_SUBAGENT_ROUTER_ENABLED`（默认 true）
- `bettercli.subagent.router.min.confidence` / `BETTERCLI_SUBAGENT_ROUTER_MIN_CONFIDENCE`（默认 0.70）
- `bettercli.subagent.router.provider` / `BETTERCLI_SUBAGENT_ROUTER_PROVIDER`
- `bettercli.subagent.router.model` / `BETTERCLI_SUBAGENT_ROUTER_MODEL`
- `bettercli.subagent.webhook.url` / `BETTERCLI_SUBAGENT_WEBHOOK_URL`
- `bettercli.wechat.subagent.router` / `BETTERCLI_WECHAT_SUBAGENT_ROUTER`（默认 false）

## 定义位置

后者覆盖同名：builtin cache → `~/.bettercli/agents/` → `.bettercli/agents/`。

同目录可选 `SOUL.md` / `IDENTITY.md` / `MEMORY.md`。

### 脚手架

```text
/subagent create <name> [--project|--user] [--template blank|code-reviewer|researcher] [--force]
/subagent templates
```

## 执行约束

- 禁止递归；ThreadLocal；进出还原 `currentModel`
- 异步占位 `CUSTOM_SUBAGENT_PENDING:` + `materializeAsyncResults`
- 路由/硬指定：`seedParentHistory`；未命中清空 sticky
- ESC/`/cancel` → `cancelAllPending`
- 独立 `SkillContextBuffer`
- `write_subagent_memory` 路径围栏
- 审计 JSONL + 可选 Webhook

## 实现入口

`com.bettercli.subagent.*` · `CustomSubAgentBootstrap` · `Main` / `WechatAgentSession` 入站 · `SubAgent.seedParentHistory`
