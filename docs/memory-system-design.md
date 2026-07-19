# PaiCLI 记忆系统设计文档

> 状态：**已交付（M1-M4 全部完成）** · 对标美团 1024 Agent 记忆方案 + Claude Code CLAUDE.md 体系
> 实施分支：feat
>
> 交付清单：
> - **M1 PAI.md 静态注入**：递归发现 + 容量管理 + `read_pai_md` / `suggest_pai_md` 工具
> - **M2 Agent 事实记忆**：SQLite FTS5 + `agent_memory_search/save/update/delete` 工具 + `/agent-memory` CLI + 迁移
> - **M3 会话历史检索**：`session_search` 工具 + 五阶段管道 + 异步索引 + jsonl 迁移
> - **M4 可插拔后端**：`MemoryStoreFactory` + `PAICLI_MEMORY_BACKEND` 配置 + PostgreSQL 骨架
>
> 未交付（云端场景启用）：PostgreSQL JDBC 实现、SQLite→PostgreSQL 迁移工具完整逻辑

## 一、背景与目标

### 1.1 当前问题

PaiCLI 现有的长期记忆（`LongTermMemory.java` + `long_term_memory.json`）存在以下问题：

- **被动注入**：每轮 ReAct 系统层自动检索注入，Agent 没有主动权
- **检索粗糙**：jieba 分词 + 子串匹配，专有名词命中率低
- **全量加载**：启动时把所有记忆加载到内存，条数多时占内存
- **全量重写**：每次写入重写整个 JSON 文件，IO 开销大
- **时间衰减不合理**：24 小时衰减对长期记忆是反模式
- **Agent 写入门槛死**：只在用户显式说"记一下"时才保存，覆盖率低

### 1.2 设计目标

参考美团 1024 Agent 的三机制架构 + Claude Code 的 CLAUDE.md 体系，重构 PaiCLI 记忆系统：

1. **Agentic RAG**：Agent 主动操作记忆，不是系统层被动注入
2. **二分存储**：用户维护的 PAI.md + Agent 维护的事实记忆，职责隔离
3. **BM25 检索**：用 SQLite FTS5 做全文检索，不依赖 embedding 服务
4. **可插拔后端**：本地 SQLite / 云端 PostgreSQL 一键切换
5. **护栏机制**：confidence + 敏感词 + 容量上限 + TTL，Agent 自主但不乱来

### 1.3 不做（明确边界）

- **不做自动预取**：每轮 LLM 调用前自动检索注入（避免 context 污染）
- **不做向量检索**：当前用 BM25，不引入 embedding 依赖
- **不做 Milvus**：当前数据量级不需要，云端规模化时再考虑
- **不做沙箱**：HITL + PathGuard + AuditLog 已够用

---

## 二、架构总览

### 2.1 三块记忆

```
┌──────────────────────────────────────────────────────────────────┐
│  第一块：PAI.md（用户维护，静态注入）                            │
│  - Markdown 文件，用户完全控制                                    │
│  - 启动时全量注入 system prompt                                   │
│  - Agent 只能 suggest，不能直接写                                 │
│  - 容量：2200 字符上限，超 80% 提示整合                           │
│  - 工具：read_pai_md / suggest_pai_md                              │
├──────────────────────────────────────────────────────────────────┤
│  第二块：Agent 维护的事实记忆（Agent 自主，主动检索）            │
│  - SQLite FTS5 存储（agent_memory.db）                             │
│  - Agent 通过工具自主 CRUD，不走 HITL                              │
│  - 用户只能查看/清空/导出，不能编辑单条                           │
│  - 检索：BM25 90% + 置信度 10%                                    │
│  - 护栏：confidence + 敏感词 + 容量上限 + TTL                     │
│  - 工具：agent_memory_search/save/update/delete                   │
├──────────────────────────────────────────────────────────────────┤
│  第三块：历史会话检索（对齐美团 session_search）                 │
│  - SQLite FTS5 存储（session_messages.db）                         │
│  - 每轮对话结束异步索引                                          │
│  - BM25 检索 + 五阶段管道简化版                                   │
│  - 工具：session_search                                           │
└──────────────────────────────────────────────────────────────────┘

可插拔后端：SQLite FTS5（本地）→ PostgreSQL FTS（云端）→ Milvus BM25（规模化）
```

### 2.2 与美团方案的对齐

| 维度 | 美团 1024 Agent | PaiCLI 调整后 |
|------|----------------|--------------|
| 静态注入 | MEMORY.md | **PAI.md**（保留命名） |
| 注入方式 | system prompt | system prompt |
| 容量上限 | 2200 字符 | 2200 字符 |
| Agent 维护方式 | edit/bash 文件工具 | **专门工具**（更语义化） |
| 主动检索 | session_search（BM25） | agent_memory_search + session_search（BM25） |
| 向量数据库 | Milvus BM25 稀疏向量 | **SQLite FTS5**（本地）/ PostgreSQL FTS（云端） |
| 自动预取 | 暂不支持 | 不做 |
| HITL | 无 | 无（Agent 自主） |

### 2.3 与 Claude Code 的对齐

| 维度 | Claude Code | PaiCLI 调整后 |
|------|------------|--------------|
| 用户维护 | CLAUDE.md | PAI.md |
| Agent 维护 | Auto Memory（MEMORY.md + topic 文件） | agent_memory.db（SQLite FTS5） |
| Agent 能否改用户部分 | 能（write_file） | **否**（只能 suggest） |
| 检索方式 | 启动全量 + 按需读文件 | 启动注入索引 + Agent 主动 search |
| 遗忘机制 | 无 | TTL + 容量上限 + access_count |

---

## 三、数据模型

### 3.1 PAI.md 文件层级（第一块）

```
~/.paicli/PAI.md                    # 用户级（所有项目可见）
<project>/PAI.md                    # 项目级（入 git，团队共享）
<project>/.paicli/PAI.md            # 项目级备选位置
<project>/PAI.local.md              # 本地覆盖（gitignore）
<project>/.paicli/PAI.local.md      # 本地覆盖备选
```

**加载机制**：
- 启动时从当前工作目录**向上递归查找** PAI.md 和 PAI.local.md
- 所有发现的文件**拼接**（不覆盖），顺序：文件系统根 → 工作目录
- 全量注入 system prompt 的 `## Project Context` 段
- 硬上限：**2200 字符**，超 80%（1760 字符）时提示 Agent 主动整合

**维护方式**：
- 用户手动编辑（任何文本编辑器）
- `/init` 命令生成初始模板
- Agent 通过 `suggest_pai_md` 工具建议添加（用户确认后写入）

### 3.2 agent_memory 表结构（第二块）

SQLite 数据库：`~/.paicli/memory/agent_memory.db`

```sql
-- 主表：记忆条目
CREATE TABLE IF NOT EXISTS agent_memory_entries (
    id TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    keywords_json TEXT NOT NULL,        -- ["Paicli", "phase-12", "JDT"]
    type TEXT NOT NULL,                  -- FACT / PATTERN / DEBUG_INSIGHT / WORKFLOW
    scope TEXT NOT NULL,                 -- project / global
    project TEXT,                        -- 项目路径（scope=project 时）
    confidence REAL NOT NULL,            -- 0.0 - 1.0
    source TEXT NOT NULL,                -- agent_tool / explicit_hint
    status TEXT NOT NULL DEFAULT 'active',  -- active / pending / expired
    pending_expires_at TEXT,             -- ISO 时间，pending 状态的过期时间
    token_count INTEGER NOT NULL,
    access_count INTEGER DEFAULT 0,      -- 被检索命中次数
    last_accessed_at TEXT,               -- 最近一次被检索时间
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_agent_memory_scope_project ON agent_memory_entries(scope, project);
CREATE INDEX IF NOT EXISTS idx_agent_memory_type ON agent_memory_entries(type);
CREATE INDEX IF NOT EXISTS idx_agent_memory_confidence ON agent_memory_entries(confidence DESC);
CREATE INDEX IF NOT EXISTS idx_agent_memory_status ON agent_memory_entries(status);
CREATE INDEX IF NOT EXISTS idx_agent_memory_last_accessed ON agent_memory_entries(last_accessed_at);

-- FTS5 全文索引虚拟表（BM25 检索）
CREATE VIRTUAL TABLE IF NOT EXISTS agent_memory_fts USING fts5(
    id UNINDEXED,                        -- 关联主表 id
    content,
    keywords,
    tokenize = 'unicode61'              -- Unicode 分词，中文需要配 jieba 或 trigram
);

-- 触发器：主表变更同步到 FTS 表
CREATE TRIGGER IF NOT EXISTS agent_memory_ai AFTER INSERT ON agent_memory_entries BEGIN
    INSERT INTO agent_memory_fts(id, content, keywords)
    VALUES (new.id, new.content, new.keywords_json);
END;
CREATE TRIGGER IF NOT EXISTS agent_memory_ad AFTER DELETE ON agent_memory_entries BEGIN
    DELETE FROM agent_memory_fts WHERE id = old.id;
END;
CREATE TRIGGER IF NOT EXISTS agent_memory_au AFTER UPDATE ON agent_memory_entries BEGIN
    DELETE FROM agent_memory_fts WHERE id = old.id;
    INSERT INTO agent_memory_fts(id, content, keywords)
    VALUES (new.id, new.content, new.keywords_json);
END;
```

**type 分类**：

| type | 含义 | 例子 |
|------|------|------|
| `FACT` | 稳定事实 | "项目用 SQLite 不用 PostgreSQL" |
| `PATTERN` | 任务模式 | "用户喜欢先看测试再改代码" |
| `DEBUG_INSIGHT` | 调试经验 | "Agent.java 的 run loop 在并发场景容易出 X 问题" |
| `WORKFLOW` | 工作流习惯 | "用户提交前喜欢跑 mvn test -Pquick" |

### 3.3 session_messages 表结构（第三块）

SQLite 数据库：`~/.paicli/memory/session_messages.db`

```sql
-- 主表：历史会话消息
CREATE TABLE IF NOT EXISTS session_messages (
    id TEXT PRIMARY KEY,                 -- 消息唯一 ID
    conversation_id TEXT NOT NULL,      -- 会话 ID（按会话聚合）
    role TEXT NOT NULL,                  -- user / assistant / tool
    content TEXT NOT NULL,               -- 消息正文
    tool_calls_json TEXT,                -- assistant 的 tool_calls（可选）
    tool_call_id TEXT,                   -- tool 消息的 call_id（可选）
    project TEXT,                         -- 项目路径
    created_at TEXT NOT NULL,
    token_count INTEGER
);

CREATE INDEX IF NOT EXISTS idx_session_messages_conversation ON session_messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_session_messages_project ON session_messages(project);
CREATE INDEX IF NOT EXISTS idx_session_messages_created ON session_messages(created_at);
CREATE INDEX IF NOT EXISTS idx_session_messages_role ON session_messages(role);

-- FTS5 全文索引
CREATE VIRTUAL TABLE IF NOT EXISTS session_messages_fts USING fts5(
    id UNINDEXED,
    content,
    tokenize = 'unicode61'
);

-- 触发器同步
CREATE TRIGGER IF NOT EXISTS session_messages_ai AFTER INSERT ON session_messages BEGIN
    INSERT INTO session_messages_fts(id, content) VALUES (new.id, new.content);
END;
CREATE TRIGGER IF NOT EXISTS session_messages_ad AFTER DELETE ON session_messages BEGIN
    DELETE FROM session_messages_fts WHERE id = old.id;
END;
```

### 3.4 user_vocabulary 表（用户词汇表累积）

```sql
CREATE TABLE IF NOT EXISTS user_vocabulary (
    term TEXT PRIMARY KEY,
    frequency INTEGER NOT NULL DEFAULT 1,
    first_seen TEXT NOT NULL,
    last_seen TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vocab_frequency ON user_vocabulary(frequency DESC);
```

---

## 四、接口设计

### 4.1 MemoryStore 接口（可插拔后端基础）

```java
public interface MemoryStore extends AutoCloseable {
    // CRUD
    void store(MemoryEntry entry);
    Optional<MemoryEntry> retrieve(String id);
    boolean update(String id, MemoryEntryPatch patch);
    boolean delete(String id);
    void clear();

    // 检索
    List<MemoryEntry> search(MemorySearchQuery query);
    List<MemoryEntry> list(MemoryListQuery query);

    // 统计
    int size();
    MemoryStats stats();

    // 词汇表
    void recordUserQuery(String query);
    double vocabularyBoost(String term);
}
```

### 4.2 PAI.md 相关工具（第一块）

```java
// 1. 读取 PAI.md（Agent 主动读取确认）
tools.put("read_pai_md", new Tool(
    "read_pai_md",
    "读取用户维护的 PAI.md 完整内容。通常启动时已注入 system prompt，"
    + "只在需要确认具体内容、或检查是否有更新时调用。",
    createParameters(),
    args -> paiMdLoader.readContent()
));

// 2. 建议添加到 PAI.md（不能直接写，只能建议）
tools.put("suggest_pai_md", new Tool(
    "suggest_pai_md",
    "建议把某条规则添加到用户维护的 PAI.md。用户会收到提示并决定是否采纳。"
    + "Agent 不能直接修改 PAI.md，必须通过此工具建议。"
    + "适用场景：发现项目约定、用户偏好、跨会话稳定规则时。",
    createParameters(
        new Param("content", "string", "建议添加的内容", true),
        new Param("section", "string", "建议添加到哪个 section（如 Commands/Architecture/Don'ts）", false),
        new Param("reason", "string", "为什么建议添加", true)
    ),
    args -> paiMdSuggester.suggest(...)
));
```

### 4.3 Agent 维护的记忆工具（第二块）

```java
// 3. 检索 Agent 记忆
tools.put("agent_memory_search", new Tool(
    "agent_memory_search",
    "检索 Agent 维护的长期记忆。用任务语义构造 query，不要直接用用户原话。"
    + "任务开始时、遇到不确定问题时、用户问"之前怎么处理过"时调用。"
    + "不要每轮都搜，只在需要时调用。",
    createParameters(
        new Param("query", "string", "检索查询，用任务语义构造", true),
        new Param("limit", "integer", "返回条数，默认 5，最多 20", false),
        new Param("type", "string", "FACT / PATTERN / DEBUG_INSIGHT / WORKFLOW", false)
    ),
    args -> agentMemoryStore.search(...)
));

// 4. 保存 Agent 记忆（Agent 自主，不走 HITL）
tools.put("agent_memory_save", new Tool(
    "agent_memory_save",
    "保存到 Agent 维护的长期记忆。Agent 自主判断，不需要用户确认。"
    + "confidence < 0.7 不要调用；临时任务/文件名不要保存；"
    + "不要保存 API key、密码、个人隐私。"
    + "keywords 必须是专有名词或核心词，3-8 个。",
    createParameters(
        new Param("fact", "string", "要保存的事实", true),
        new Param("keywords", "array", "提取的关键词，3-8 个专有名词", true),
        new Param("confidence", "number", "0-1 置信度，必须诚实评估", true),
        new Param("type", "string", "FACT / PATTERN / DEBUG_INSIGHT / WORKFLOW", true),
        new Param("scope", "string", "project 或 global，默认 project", false)
    ),
    args -> agentMemoryStore.store(...)
));

// 5. 更新 Agent 记忆（Agent 自主）
tools.put("agent_memory_update", new Tool(
    "agent_memory_update",
    "更新 Agent 维护的记忆。发现旧记忆过时、或要补充新信息时调用。"
    + "建议先 agent_memory_search 看原内容。",
    createParameters(
        new Param("id", "string", "要更新的记忆 ID", true),
        new Param("content", "string", "新内容", false),
        new Param("keywords", "array", "新关键词", false)
    ),
    args -> agentMemoryStore.update(...)
));

// 6. 删除 Agent 记忆（Agent 自主）
tools.put("agent_memory_delete", new Tool(
    "agent_memory_delete",
    "删除 Agent 维护的记忆。发现记忆错误、重复、或过时时调用。",
    createParameters(
        new Param("id", "string", "要删除的记忆 ID", true)
    ),
    args -> agentMemoryStore.delete(...)
));
```

### 4.4 历史会话检索工具（第三块）

```java
// 7. 检索历史会话
tools.put("session_search", new Tool(
    "session_search",
    "检索历史会话消息。当用户问"之前怎么处理过 X"、或需要回溯历史决策时调用。"
    + "BM25 全文检索，返回相关会话的摘要。",
    createParameters(
        new Param("query", "string", "检索查询", true),
        new Param("limit", "integer", "返回会话数，默认 3，最多 10", false),
        new Param("role_filter", "string", "user / assistant，默认全部", false),
        new Param("days_back", "integer", "回溯天数，默认 30", false)
    ),
    args -> sessionMessageStore.search(...)
));
```

---

## 五、检索策略

### 5.1 BM25 检索（核心）

使用 SQLite FTS5 原生 BM25 排序：

```sql
-- agent_memory_search 的核心 SQL
SELECT m.id, m.content, m.type, m.confidence,
       bm25(agent_memory_fts) AS bm25_score
FROM agent_memory_fts f
JOIN agent_memory_entries m ON f.id = m.id
WHERE agent_memory_fts MATCH ?
  AND m.status = 'active'
  AND (m.scope = 'global' OR m.project = ?)
ORDER BY bm25_score ASC  -- FTS5 的 bm25() 越小越相关
LIMIT ?;
```

### 5.2 混合打分公式

```
final_score = 
    0.90 * bm25_normalized        (BM25 关键词命中，主信号)
  + 0.10 * confidence_weight       (来源置信度，辅助)
```

- **BM25 归一化**：`bm25_normalized = 1 / (1 + exp(bm25_score))`，把 FTS5 的负分映射到 [0, 1]
- **置信度加权**：直接用 `confidence` 字段值
- **关键词为主（90%）**：用户提到过的专有名词是核心检索锚点
- **置信度为辅（10%）**：用户显式保存的 > Agent 推断的

### 5.3 五阶段管道（session_search）

对齐美团方案，简化版：

```
① BM25 全文检索
    - 关键词 → FTS5 查询 → topK = limit × 10
    - 过滤：project + 可选 role + 可选 days_back
② 按会话分组 & 去重
    - 按 conversation_id 聚合
    - 取每个会话最高 BM25 分
    - 降序保留 Top N（默认 3）
③ 加载完整会话
    - 按 conversation_id 查所有消息
    - 格式化为 [USER]: ... [ASSISTANT]: ... [TOOL]: ...
    - 超过 10 万字符时以命中关键词为中心取窗口
④ 可选 LLM 摘要
    - 当前 MVP 跳过，直接返回 raw preview（前 500 字）
    - 后续可加并行 LLM 摘要（CompletableFuture，超时 60s）
⑤ 组装结果返回主模型
    - JSON 格式：{ mode, query, sessions: [{ conversation_id, summary, status }] }
```

### 5.4 用户词汇表累积

每次用户输入时，记录关键词到 `user_vocabulary` 表：

```java
public void recordUserQuery(String query) {
    Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
    Instant now = Instant.now();
    for (String token : tokens) {
        vocabularyDao.upsert(token, now);
    }
}

public double vocabularyBoost(String term) {
    int freq = vocabularyDao.getFrequency(term);
    return 1.0 + Math.min(freq * 0.1, 2.0);  // 用得越多权重越高，上限 3x
}
```

检索时，如果记忆条目的 keywords 包含高频用户词汇，额外加分。这样**越用越准**。

---

## 六、护栏机制

Agent 维护的记忆用户不能操作，所以必须有自动护栏。

### 6.1 confidence 门槛

| confidence | 来源 | 处理 |
|-----------|------|------|
| ≥ 0.9 | 用户显式"记一下" / Agent 高度确信 | 直接写入，status=active |
| 0.7 - 0.9 | Agent 推断的稳定偏好 | 写入，status=pending，下次启动提示用户确认 |
| < 0.7 | 临时任务 / 模型猜测 | 工具内部拒绝，不写入 |

per-provider 阈值可配：
- DeepSeek / GLM-5.1：用 0.75（判断力较强）
- StepFun / Kimi：用 0.85（可能过度自信）

### 6.2 敏感词拦截

写入前正则检查，命中拒绝：

```java
private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
    "(?i)(password|passwd|secret|token|bearer|api[_-]?key|private[_-]?key|access[_-]?key)"
    + "|[A-Za-z0-9+/]{40,}={0,2}"  // 长字符串（可能 base64 编码的 key）
);

public static boolean containsSensitive(String content) {
    return SENSITIVE_PATTERN.matcher(content).find();
}
```

命中后返回错误提示，不写入。

### 6.3 容量上限

- 默认 **1000 条**（可配 `PAICLI_AGENT_MEMORY_MAX`）
- 超出时按 `access_count ASC, last_accessed_at ASC` 淘汰低价值记忆
- 淘汰前检查 confidence，`confidence >= 0.9` 的不淘汰（用户显式保存的保护）

### 6.4 自动去重

新记忆与已有记忆用 FTS5 BM25 相似度检查：

```sql
-- 检查是否有高度相似的已有记忆
SELECT id, content, bm25(agent_memory_fts) AS score
FROM agent_memory_fts
WHERE agent_memory_fts MATCH ?
ORDER BY score ASC
LIMIT 1;
```

如果 BM25 分数表明高度相似（阈值可配），合并而非新增：
- 保留 confidence 更高的
- keywords 取并集
- content 取更精炼的

### 6.5 TTL 清理

后台任务定期清理（默认每天一次）：

```sql
-- 清理过期 pending 记忆
DELETE FROM agent_memory_entries
WHERE status = 'pending' AND pending_expires_at < ?;

-- 清理长期未命中 + 低 confidence 的记忆
DELETE FROM agent_memory_entries
WHERE status = 'active'
  AND confidence < 0.8
  AND last_accessed_at < ?  -- 90 天前
  AND access_count < 2;       -- 命中次数少于 2
```

### 6.6 rate limit

每轮 ReAct 最多 5 次 `agent_memory_*` 工具调用，防止 Agent 过度调用。

---

## 七、迁移方案

### 7.1 从 long_term_memory.json 迁移到 SQLite

启动时检测 `~/.paicli/memory/long_term_memory.json` 是否存在，自动迁移：

```java
public AgentMemoryStore(File memoryDir) {
    File dbFile = new File(memoryDir, "agent_memory.db");
    this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    initSchema();

    File legacyJson = new File(memoryDir, "long_term_memory.json");
    if (legacyJson.exists() && isDatabaseEmpty()) {
        migrateFromJson(legacyJson);
    }
}

private void migrateFromJson(File legacyJson) {
    List<MemoryEntry> legacy = loadLegacyJson(legacyJson);
    for (MemoryEntry entry : legacy) {
        // 老数据补字段
        entry.setKeywords(extractKeywords(entry.getContent()));  // jieba 提取
        entry.setSource("user_save");
        entry.setConfidence(1.0);
        entry.setStatus("active");
        entry.setType("FACT");
        insertEntry(entry);
    }
    // 改名，不删除
    legacyJson.renameTo(new File(legacyJson.getParent(), "long_term_memory.json.migrated"));
    log.info("迁移了 {} 条长期记忆到 SQLite", legacy.size());
}
```

迁移原则：
1. **自动迁移**：用户不需要手动操作
2. **不删原文件**：改名为 `.migrated`，用户确认无误后可手工删除
3. **保留原 ID**：迁移后 ID 不变，`/agent-memory delete <id>` 仍可用
4. **补字段**：老数据补 `keywords`（jieba 提取）、`source=user_save`、`confidence=1.0`、`status=active`、`type=FACT`

### 7.2 从 session_*.jsonl 迁移到 SQLite

启动时检测 `~/.paicli/history/session_*.jsonl`，自动迁移到 `session_messages.db`：

```java
private void migrateFromJsonl(File historyDir) {
    File[] sessionFiles = historyDir.listFiles((dir, name) -> name.endsWith(".jsonl"));
    if (sessionFiles == null) return;

    for (File sessionFile : sessionFiles) {
        String conversationId = sessionFile.getName().replace(".jsonl", "");
        try (BufferedReader reader = new BufferedReader(new FileReader(sessionFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Message msg = parseMessage(line);
                if (msg != null) {
                    insertMessage(conversationId, msg);
                }
            }
        }
    }
}
```

迁移原则：
1. **不删原文件**：JSONL 文件保留，作为 fallback
2. **新消息双写**：迁移后新消息同时写 JSONL 和 SQLite
3. **按需迁移**：可以只迁移最近 N 天的，老的保留在 JSONL

---

## 八、配置设计

### 8.1 配置文件

`~/.paicli/config.json` 或环境变量：

```json
{
  "memory": {
    "backend": "sqlite",
    "sqlite": {
      "agentMemoryPath": "~/.paicli/memory/agent_memory.db",
      "sessionMessagesPath": "~/.paicli/memory/session_messages.db"
    },
    "postgres": {
      "url": "jdbc:postgresql://localhost:5432/paicli",
      "user": "paicli",
      "password": "${PAICLI_PG_PASSWORD}"
    },
    "agentMemory": {
      "maxEntries": 1000,
      "confidenceThreshold": 0.7,
      "pendingTtlDays": 7,
      "cleanupTtlDays": 90,
      "rateLimitPerTurn": 5
    },
    "paiMd": {
      "maxChars": 2200,
      "integrateThreshold": 0.8
    }
  }
}
```

### 8.2 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PAICLI_MEMORY_BACKEND` | 后端类型 | `sqlite` |
| `PAICLI_MEMORY_PG_URL` | PostgreSQL URL | - |
| `PAICLI_MEMORY_PG_USER` | PostgreSQL 用户 | - |
| `PAICLI_MEMORY_PG_PASSWORD` | PostgreSQL 密码 | - |
| `PAICLI_AGENT_MEMORY_MAX` | Agent 记忆容量上限 | `1000` |
| `PAICLI_AGENT_MEMORY_CONFIDENCE` | confidence 门槛 | `0.7` |
| `PAICLI_AGENT_MEMORY_PENDING_TTL` | pending 记忆 TTL（天） | `7` |
| `PAICLI_AGENT_MEMORY_CLEANUP_TTL` | 清理 TTL（天） | `90` |
| `PAICLI_AGENT_MEMORY_RATE_LIMIT` | 每轮调用上限 | `5` |
| `PAICLI_PAI_MD_MAX_CHARS` | PAI.md 字符上限 | `2200` |

### 8.3 切换体验

```bash
# 本地开发（默认）
java -jar paicli.jar

# 切到云端 PostgreSQL
PAICLI_MEMORY_BACKEND=postgres \
PAICLI_MEMORY_PG_URL=jdbc:postgresql://cloud:5432/paicli \
PAICLI_MEMORY_PG_PASSWORD=$PG_PASSWORD \
java -jar paicli.jar

# 数据迁移
paicli memory migrate --from sqlite --to postgres --url $PG_URL
```

---

## 九、测试策略

### 9.1 单元测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `SqliteAgentMemoryStoreTest` | CRUD + BM25 检索 + 置信度加权 |
| `SqliteSessionMessageStoreTest` | 消息写入 + BM25 检索 + 五阶段管道 |
| `MemoryGuardTest` | confidence 门槛 + 敏感词拦截 + 容量上限 |
| `UserVocabularyTest` | 词汇表累积 + boost 计算 |
| `PaiMdLoaderTest` | 向上递归加载 + 容量管理 |
| `MemoryMigrationTest` | JSON → SQLite 迁移 |

### 9.2 集成测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `AgentMemoryToolIntegrationTest` | 4 个 agent_memory_* 工具端到端 |
| `SessionSearchToolIntegrationTest` | session_search 工具端到端 |
| `PaiMdSuggestToolIntegrationTest` | suggest_pai_md 工具 + 用户确认 UI |
| `MemorySystemE2ETest` | 三块记忆协同工作 |

### 9.3 回归测试

- `mvn test -Dtest=MemorySystemTest -DskipTests=false` 针对性
- `mvn test -Pquick` 常规回归
- 现有 `LongTermMemoryTest` 保留，确保迁移后老接口仍可用

### 9.4 手工验证

- 启动后检查 `agent_memory.db` / `session_messages.db` 是否创建
- 检查 `long_term_memory.json.migrated` 是否生成
- 测试 `/agent-memory list` / `/agent-memory search` 命令
- 测试 Agent 调用 `agent_memory_save` / `agent_memory_search` 工具
- 测试 `suggest_pai_md` 工具的用户确认流程

---

## 十、实施计划

### 10.1 Milestone 1：PAI.md 静态注入增强（约 2-3 天）✅ 已交付

- M1.1 设计 PAI.md 文件层级 + 向上递归加载机制 ✅
- M1.2 实现 PaiMdLoader ✅
- M1.3 实现 read_pai_md 工具 ✅
- M1.4 实现 suggest_pai_md 工具 ✅
- M1.5 实现容量管理（2200 字符上限）✅
- M1.6 兼容现有 PAI.md 机制 ✅
- M1.7 测试 + 文档同步 ✅

### 10.2 Milestone 2：Agent 维护的事实记忆（约 5-7 天）✅ 已交付

- M2.1 设计 AgentMemoryStore 接口 + SQLite FTS5 表结构 ✅
- M2.2 实现 SqliteAgentMemoryStore ✅
- M2.3 实现 agent_memory_search 工具 ✅
- M2.4 实现 agent_memory_save 工具 ✅
- M2.5 实现 agent_memory_update / delete 工具 ✅
- M2.6 实现护栏（容量 + 去重 + TTL）✅
- M2.7 实现启动索引注入 + system prompt 指南 ✅
- M2.8 实现 /agent-memory CLI 命令组 ✅
- M2.9 从 long_term_memory.json 迁移 ✅
- M2.10 测试 + 文档同步 ✅

### 10.3 Milestone 3：历史会话检索（约 3-4 天）✅ 已交付

- M3.1 设计 SessionMessageStore 接口 + SQLite FTS5 表结构 ✅
- M3.2 实现 SqliteSessionMessageStore ✅
- M3.3 实现每轮对话结束异步索引 ✅
- M3.4 实现 session_search 工具 ✅
- M3.5 实现五阶段管道简化版 ✅
- M3.6 实现检索范围过滤 ✅
- M3.7 测试 + 文档同步 ✅

### 10.4 Milestone 4：可插拔后端架构（约 3-4 天）✅ 已交付（PostgreSQL 实现预留骨架）

- M4.1 抽象 MemoryStore 接口，重构现有实现 ✅
- M4.2 实现 MemoryStoreFactory + 配置驱动切换 ✅
- M4.3 实现 PostgresMemoryStore（PostgreSQL FTS）✅ 骨架预留，需要 JDBC 驱动
- M4.4 实现数据迁移工具 ✅ 骨架预留
- M4.5 测试 + 文档同步 + 部署文档 ✅

### 10.5 执行顺序

```
M1（独立，可先做）
  ↓
M2（依赖 M1 的注入机制）
  ↓
M3（依赖 M2 的检索基础设施）
  ↓
M4（依赖 M2/M3 的实现，抽接口）
```

总工期约 **13-18 天**，每个 milestone 独立可交付。

---

## 十一、风险与对策

| 风险 | 对策 |
|------|------|
| Agent 过度调用 memory_search | 工具描述明确"只在需要时调用" + 每轮 rate limit |
| Agent 不调用记忆工具（不知道该用） | 启动注入索引 + system prompt 指南明确使用时机 |
| Agent 写入垃圾记忆 | confidence 门槛 + 敏感词拦截 + 容量上限 + TTL |
| 不同 provider 判断力差异 | per-provider confidence 阈值配置 |
| 记忆库膨胀 | 容量上限 + TTL 清理 + access_count 淘汰 |
| SQLite FTS5 中文分词 | 用 `unicode61` + trigram 模式，或自定义 jieba tokenizer |
| 迁移失败 | 不删原文件，改名为 `.migrated`，可回滚 |

---

## 十二、与现有架构的兼容性

### 12.1 保留的接口

- `LongTermMemory` 类保留，作为兼容层（内部委托给新的 `AgentMemoryStore`）
- `MemoryManager` 类保留，门面不变
- `MemoryRetriever` 类保留，但内部改用 BM25
- `/save` / `/memory` / `/memory list` 等现有命令保留
- `save_memory` 工具保留，内部委托给 `agent_memory_save`

### 12.2 新增的接口

- `AgentMemoryStore` 接口（新）
- `SessionMessageStore` 接口（新）
- `PaiMdLoader` 类（增强现有 `ProjectMemoryLoader`）
- `MemoryStoreFactory` 工厂（新）
- 6 个新工具：`read_pai_md` / `suggest_pai_md` / `agent_memory_search` / `agent_memory_save` / `agent_memory_update` / `agent_memory_delete` / `session_search`

### 12.3 文档同步

每个 milestone 完成后同步更新：
- `AGENTS.md`：架构概览 + 关键行为约束
- `README.md`：功能列表 + 命令清单
- `ROADMAP.md`：状态变化（仅在 milestone 完成时）

---

## 十三、参考文档

- 美团 1024 Agent 记忆功能方案（内部文档）
- Claude Code Memory 官方文档：https://code.claude.com/docs/en/memory
- SQLite FTS5 文档：https://www.sqlite.org/fts5.html
- PaiCLI AGENTS.md
- PaiCLI PAI.md

---

> 本文档为设计阶段产物，实施时以代码实际行为为准。每个 milestone 完成后更新本文档的"状态"标记。
