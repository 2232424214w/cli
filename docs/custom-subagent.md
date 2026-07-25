# Custom Subagent

与 Multi-Agent（`/team` / `run_team` 固定 Planner + Worker×2 + Reviewer）**独立**的可定义子 Agent 能力。

## 触发原则

| 方式 | 是否支持 | 说明 |
|------|----------|------|
| 主 Agent 语义委托 `run_subagent` | 是 | 异步占位 → 批次结束回填；状态栏显示 `sa:name` / `sa×N` |
| 轻量路由 LLM | 是 | 命中则跳过主 Agent；**置信度门控**（默认 ≥0.70）；sticky 短跟进；失败 fail-open |
| `@main` / `/main` 强制主 Agent | 是 | 剥前缀后走主 ReAct，并清空 sticky |
| `/subagent <name> <task>` 硬指定执行 | **否** | 故意不做 |
| `/subagent` / `list` / `reload` / `status`（`/sa-st`） | 仅管理 | — |

配置：

- `bettercli.subagent.router.enabled` / `BETTERCLI_SUBAGENT_ROUTER_ENABLED`（默认 true）
- `bettercli.subagent.router.min.confidence` / `BETTERCLI_SUBAGENT_ROUTER_MIN_CONFIDENCE`（默认 0.70）

路由 LLM 回复格式：`name|0.85` 或 `NONE`。

## 定义位置

后者覆盖同名：builtin cache → `~/.bettercli/agents/` → `.bettercli/agents/`。

同目录可选 `SOUL.md` / `IDENTITY.md` / `MEMORY.md`。

## 执行约束（已实现）

- 禁止递归；ThreadLocal 并行安全；进出时还原 `currentModel`
- 异步占位 `CUSTOM_SUBAGENT_PENDING:` + `materializeAsyncResults`；等待时提示与状态栏 phase
- 路由模式：`SubAgent.seedParentHistory` 继承主会话近期 user/assistant；未命中清空 sticky
- ESC/`/cancel`：`CancellationContext` + `cancelAllPending()` 中断后台 Future
- 独立 `SkillContextBuffer`（不与主会话共享）
- `write_subagent_memory`：仅允许 agents/agents-cache 下的 `MEMORY.md`，拒绝 symlink
- Tee 进度、审计 JSONL

## CLI 相对 1024 的裁剪

斜杠硬指定任务 / RoleHub / Webhook / HA 续跑：**不做**。

## 实现入口

`com.bettercli.subagent.*` · `Agent` · `Main` 入站路由 · `SubAgent.seedParentHistory`
