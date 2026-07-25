# Custom Subagent

与 Multi-Agent（`/team` / `run_team` 固定 Planner + Worker×2 + Reviewer）**独立**的可定义子 Agent 能力。

对齐文档：`restored-docs/1024-Custom-Subagent设计方案.md`（CLI 子集）。

## 与 1024 文档对齐矩阵

| 文档能力 | BetterCLI | 说明 |
|----------|-----------|------|
| 独立 prompt / 工具 / 模型 / maxTurns / skills / MEMORY / SOUL / IDENTITY | ✅ | `AGENT.md` + sidecar |
| 方式一：`run_subagent` 委托 + 并发 + 隔离历史 | ✅ | 异步占位 + 批次回填 |
| 方式二：路由 LLM 跳过主 Agent | ✅ | 置信度门控 + sticky + 专用模型可配 |
| 方式三：`/subagent:name` / `/sa:name` 硬指定 | ✅ | **消息前缀**；空格形式仍禁止 |
| 未找到时列出可用 | ✅ | |
| `@main` / `/main` 强制主 Agent | ✅ | |
| 禁止递归 / 停止联动 / 超时 | ✅ | |
| 作用域覆盖 | ✅ | builtin &lt; user &lt; project |
| 本地复用基座（对标 platform） | ✅ | frontmatter `from` / `extends` |
| `/sa-l` `/sa-st` / `show` | ✅ | list / status / 查看定义 |
| 审计 + 主会话 `via:` 标注 | ✅ | JSONL + `/subagent audit`；回复前缀 `[via:name]` |
| Webhook `SUBAGENT_*` | ✅ 轻量 | `BETTERCLI_SUBAGENT_WEBHOOK_URL`，5s fail-open |
| 脚手架 + 内置样例 | ✅ | create/templates；builtin `code-reviewer` / `researcher` |
| 微信通道 | ✅ | 硬指定始终可用；路由默认关 |
| 轻量 HA 续跑 | ✅ | 会话落盘 `~/.bettercli/subagent-sessions/`；`/subagent sessions` / `resume` |
| RoleHub / belongPaas | ❌ | 平台专属 |

## 触发原则（入站优先级 = 文档 §4.1）

1. `/subagent:name …` 或 `/sa:name …` → 硬指定直达  
2. 路由 LLM → 命中则直达  
3. 主 Agent（可再 `run_subagent`）

管理命令：`list` / `reload` / `status` / `create` / `templates` / `audit` / `show` / `sessions` / `resume`；别名 `/sa-l` `/sa-st`。

`model` 支持 `provider`、`provider/model` 或 `provider:model`（如 `glm/glm-4-flash`）。

## 轻量 HA

每轮工具后把对话 checkpoint 到 `~/.bettercli/subagent-sessions/<sessionId>.json`。

```text
/subagent sessions [n]
/subagent resume [sessionId]   # 省略则取最近可恢复会话
```

## 定义与继承

```yaml
---
name: strict-reviewer
from: code-reviewer          # 或 extends
description: 更严格的审查口径
# 未写的字段（tools/model/body…）从基座继承；本文件非空字段覆盖基座
---
```

内置：`code-reviewer`、`researcher`（解压到 `~/.bettercli/agents-cache/`）。

## 脚手架

```text
/subagent create <name> [--project|--user] [--template blank|code-reviewer|researcher] [--force]
/subagent show <name>
/subagent templates
```

## 配置

- `BETTERCLI_SUBAGENT_ROUTER_*`（enabled / min.confidence / provider / model）
- `BETTERCLI_SUBAGENT_WEBHOOK_URL`
- `BETTERCLI_WECHAT_SUBAGENT_ROUTER`（默认 false）

## 实现入口

`com.bettercli.subagent.*` · `CustomSubAgentBootstrap` · `Main` / `WechatAgentSession`
