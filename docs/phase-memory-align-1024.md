# M5：记忆系统对齐 1024 Agent 技术方案

> 对照：`D:\美团文档\1024-Agent\1024-Agent-记忆功能方案\1024-Agent-记忆功能方案.md`  
> 分支：`feat-next`  
> 状态：**P0 + P1 + P2 已按本文档落地**

---

## 0. 目标与非目标

### 0.1 目标

把 BetterCLI 记忆语义对齐 1024 的三机制模型：

1. **静态注入（默认开）**：会话级常驻关键事实（BETTER.md ≈ MEMORY.md）
2. **自动预取（默认关 / 不做）**：不在每轮 LLM 前被动检索注入
3. **主动检索（默认开）**：Agent 调用 `session_search` / `agent_memory_*` 按需取回

并消除 BetterCLI 现状中的「双轨长期记忆 + 默认被动预取」噪音。

### 0.2 非目标（明确不做）

| 项 | 原因 |
|---|---|
| 群组 MEMORY.md / 群聊隔离注入 | CLI 无大象群工作区模型 |
| Milvus / 稀疏向量服务 | 本地用 SQLite FTS5 足够 |
| OpenViking / Mem0 插件宿主 | 可后续独立期次 |
| webhook `allowedSkills` / 动态权限 | 平台 Gateway 能力 |
| 把 scripts/Skill 写进记忆主路径 | 与 HITL/Skill 期次边界冲突 |

---

## 1. 现状对比（1024 vs BetterCLI）

### 1.1 机制映射

| 1024 | 1024 状态 | BetterCLI（本分支后） | 差距 |
|---|---|---|---|
| MEMORY.md 静态注入，`<user_memory>`，2200 字，80% 整合 | ✅ | `BETTER.md` + `<user_memory>` 2200；超 80% 整合指引 | 对齐 |
| 自动预取（每轮 RAG） | ❌ 暂不支持 | Legacy 预取默认关；开时优先 agent_memory BM25 | 对齐 |
| `session_search`：BM25→分组→加载→LLM 摘要→JSON | ✅ | 五阶段 + 关键词窗口 + summarize + format | 对齐 |
| 事实记忆 + 低置信待确认 | 文件/表 | `agent_memory` + PENDING/confirm/reject | 对齐 |

### 1.2 BetterCLI 已超对齐（保留）

- `agent_memory_*` CRUD + BM25 + confidence/敏感词/容量护栏
- SessionNotebook（压缩后可读回）
- 主轨 Context Checkpoint Compaction + session 索引
- `/agent-memory` / `/memory` CLI 管理面（`/memory` 委托 agent_memory）
- 可插拔后端骨架（SQLite / Postgres）

### 1.3 产品原则

```
稳定规则  → BETTER.md 静态注入（用户/团队可控）
稳定事实  → agent_memory（Agent 主动 CRUD；/save 也写这里）
任务过程  → session_search 按需回溯（不进 BETTER / 不被动预取）
```

---

## 2. 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│ system prompt（每轮）                                         │
│  ├─ Project Context                                           │
│  │   ├─ <user_memory> BETTER.md … </user_memory>  ≤2200 字   │
│  │   ├─ Agent 记忆摘要（最近 N 条，硬上限）                    │
│  │   └─ SessionNotebook 摘要（可选）                          │
│  └─ （默认无）Legacy「相关长期记忆」被动预取段                 │
└─────────────────────────────────────────────────────────────┘
                              │
          Agent 主动工具       │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
   agent_memory_*        session_search      read_better_md /
   （事实 CRUD）         （历史五阶段）      suggest_better_md
```

写入路径统一：

```
用户 /save 或 save_memory ──┐
auto_extract（opt-in）──────┼──► MemoryManager.storeFact
agent_memory_save ──────────┘         │
                                      ├─► AgentMemoryStore（主，默认唯一）
                                      └─► LongTermMemory JSON（仅 dual_write=true）
```

---

## 3. 分期与改动清单

### P0 — 统一语义 ✅

| ID | 改动 | 验收 |
|---|---|---|
| P0-1 | Legacy 预取默认关闭 | 默认 system 无 `## 相关长期记忆` |
| P0-2 | `storeFact` 写 `AgentMemoryStore` | `/save` 后 `agent_memory_search` 可命中 |
| P0-3 | `loadForPrompt` 硬截 2200 + `<user_memory>` | 超长含截断提示与标签 |
| P0-4 | 文档与 prompt 对齐 | 描述与代码一致 |

### P1 — session_search 对齐 1024 五阶段 ✅

| ID | 改动 | 验收 |
|---|---|---|
| P1-1 | keyword-centered 窗口 | 超长 transcript 不丢命中上下文 |
| P1-2 | 并行 LLM 摘要（超时降级） | `summarize=true` 含摘要 |
| P1-3 | `format=json\|markdown` | json 含 sessions 结构 |
| P1-4 | 工具 schema / prompt 更新 | Agent 知道可主动检索 |

### P2 — 体验与清理 ✅

| ID | 改动 | 验收 |
|---|---|---|
| P2-1 | `[0.7,0.9)` → PENDING；`≥0.9` ACTIVE；CLI pending/confirm/reject | pending 不可 search；confirm 后可搜 |
| P2-2 | BETTER 超 80% 整合指引 | `read_better_md` / `suggest_better_md` 含合并删除步骤 |
| P2-3 | 默认停 JSON 双写；`/memory *` 委托 agent_memory | `/save` 后 `/memory list` 经委托可见 |
| P2-4 | 预取开时优先 agent_memory BM25；`MemoryRetriever` `@Deprecated` | prefetch 上下文来自 BM25 |

配置：

| Key | 默认 | 含义 |
|---|---|---|
| `bettercli.memory.legacy_prefetch.enabled` / `BETTERCLI_MEMORY_LEGACY_PREFETCH` | `false` | 恢复旧每轮被动注入 |
| `bettercli.better_md.max_chars` / `BETTERCLI_PAI_MD_MAX_CHARS` | `2200` | 静态注入硬预算 |
| `bettercli.memory.json_dual_write.enabled` / `BETTERCLI_MEMORY_JSON_DUAL_WRITE` | `false` | `/save` 同时写旧 JSON |

---

## 4. 关键实现要点

### 4.1 PENDING 确认流

```text
agent_memory_save(confidence)
  <0.7  → 拒绝
  [0.7,0.9) → status=PENDING, pendingExpiresAt=+7d（FTS search 仅 ACTIVE）
  ≥0.9 → status=ACTIVE

/agent-memory pending
/agent-memory confirm <id>  → ACTIVE，清 TTL
/agent-memory reject <id>   → delete
```

### 4.2 `/memory` 委托

有 `AgentMemoryStore` 时：`list/search/delete/clear` 走 store，并提示改用 `/agent-memory`。

### 4.3 测试矩阵

| 套件 | 覆盖 |
|---|---|
| `ProjectMemoryLoaderTest` | `<user_memory>`、2200、整合指引 |
| `MemoryManagerTest` | 预取默认关、默认不双写 JSON、prefetch BM25 |
| `ToolRegistryTest` | PENDING 不可搜、confirm 后可搜 |
| `CliCommandParserTest` | pending/confirm/reject |
| `SessionSearchSummarizerTest` | 超时降级、format |

---

## 5. 实施顺序（严格）

1. ~~写本文档~~  
2. ~~P0~~ / ~~P1~~ / ~~P2~~  

合并门槛：P0+P1+P2 测试绿；`docs/memory-system-design.md` 状态同步为 M5 已交付。

---

## 6. 与既有文档关系

| 文档 | 角色 |
|---|---|
| `docs/memory-system-design.md` | 总设计（M1–M5）；状态段回写 |
| **本文档** | M5 对齐 1024 的实施规格（本分支权威） |
| `AGENTS.md` Memory 节 | 运行时规则摘要 |

---

## 7. 变更日志

| 日期 | 内容 |
|---|---|
| 2026-07-26 | 初版技术方案；定义 P0/P1/P2 与验收 |
| 2026-07-26 | 落地 P0（预取/双写/BETTER）+ P1（窗口/摘要/json） |
| 2026-07-26 | 落地 P2（PENDING 确认流 / 停 JSON 双写 /memory 委托 / MemoryRetriever deprecate） |
