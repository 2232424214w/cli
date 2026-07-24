# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `BETTER.md` > 4. `README.md` > 5. `ROADMAP.md` > 6. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`BetterCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 23 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入 → 微信 iLink 通道文本 MVP）
- `BETTER.md` 是 BetterCLI 的项目级记忆文件：启动时自动注入 system prompt，适合团队共享的长期稳定规则；个人/会变化的经验继续用 `/save` 长期记忆。
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`bettercli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`
- 记忆后端（可选，默认 sqlite）：`BETTERCLI_MEMORY_BACKEND` / `-Dbettercli.memory.backend`，取值 `sqlite`（本地，已交付）/ `postgres`（云端，骨架预留，需要 PostgreSQL JDBC 驱动）

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
# 全局命令（对标 Claude Code 的 `claude`）：安装后任意目录可直接运行
# Windows:  .\scripts\install.ps1
# Unix:     ./scripts/install.sh
bettercli                # 交互式 CLI（安装后）
/lang zh                 # 界面与回复默认中文（默认）；/lang en 切英文
bettercli wechat setup   # 主动绑定微信 iLink 通道，默认不开启
bettercli wechat start   # 前台启动微信通道
# 未安装时仍可用 jar 直启
java -jar target/bettercli-1.0-SNAPSHOT.jar
/wechat                   # 交互式 CLI 内扫码绑定并后台启动微信通道
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
/init                    # 生成精简项目级记忆 BETTER.md；已有文件不覆盖，/init --force 可重写
/export                  # 导出当前 ReAct 会话为 Markdown，包含完整 system prompt
/agent-memory            # 查看 Agent 维护的事实记忆统计；/agent-memory list/search/stats/export/clear 管理视图
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

Plan-and-Execute DAG 校验：`ExecutionPlan.validate()` 返回 `PlanValidationResult`（悬空依赖 / 自依赖 / 环三类问题正交），`detectCycle()` 返回环路径（跳过自环与悬空边，避免误判）。`Planner.parsePlan` 解析期自动修复可恢复项（自依赖、悬空依赖丢弃 + `log.warn`），真环抛带路径的 `IOException`（"存在循环依赖: task_1 -> task_2 -> task_1"）。`Planner.replan` 保留原目标（失败上下文作 `createPlan(goal, extraContext)` 补充，不再污染 `plan.getGoal()`）。`PlanExecuteAgent` 计划停滞时列出被卡的 PENDING 任务 id。`inferSimpleTaskType` 已修复 `&&`/`||` 优先级 bug（读取/打开/查看三个动词一致判 FILE_READ）。详见 `docs/plan-module-iteration.md`。

Multi-Agent 角色工具白名单（`AgentRole.allowedTools()`）：PLANNER 只读+调研（`read_file`/`glob_files`/`grep_code`/`list_dir`/`web_search`/`web_fetch`），REVIEWER 纯只读（`read_file`/`glob_files`/`grep_code`/`list_dir`，不联网不写不执行），WORKER 返回 `null` 表示不限制（全量内置 + MCP）。白名单两处生效：`ToolRegistry.getToolDefinitions(whitelist)` 只把白名单内工具 schema 下发给 LLM；`executeTools(invocations, whitelist)` 在执行层拦截越权调用（含 mcp__*），防御 LLM 幻觉出白名单外工具名。`SubAgent` 不再用 `shouldUseTools()` 布尔，改用 `role.allowedTools()`；`team-planner.md` / `team-reviewer.md` 已声明可用只读工具并要求规划/审查前先核实代码。
Multi-Agent 角色级模型分配（`RoleModelResolver`）：`AgentOrchestrator.setRoleClientResolver(Function<AgentRole, LlmClient>)` 让 Planner / Reviewer / Worker 用不同模型，配置走 `bettercli.team.<role>.provider` 系统属性或 `BETTERCLI_TEAM_<ROLE>_PROVIDER` 环境变量，未配或建不出来时回退主模型（向后兼容）。`/team` 启动时打印三角色模型标签；`roleModelLabel(role)` 供状态展示与 ablation 记录。`setRoleClientResolver` 会重建 SubAgent 并重新下发已设置的 Skill 系统与外部上下文。
Multi-Agent Worker 分工与持久记忆：`AgentOrchestrator.setWorkerSpecialties(List<String>)` 给 worker-1/worker-2 注入差异化专长（默认按能力维度：实现 vs 分析/验证，可用 `bettercli.team.worker.specialties` / `BETTERCLI_TEAM_WORKER_SPECIALTIES` 覆盖）；团队名单 + 专长通过 `{{teamWorkers}}` 注入 `team-planner.md`，规划者在每个 step 的 `assignee` 指定最匹配的 Worker。`ExecutionStep` 新增 `assignee` 字段，`parsePlan` 读取并经 `normalizeAssignee` 过滤幻觉出的不存在 worker 名（回退默认调度）。串行/并行两条路径都按 assignee 路由（`pickWorker` / `takeWorker`），指派命中时打印 🎯 提示。Worker 不再每步 `clearHistory`——保留跨步骤对话记忆，超 window 时由 `SubAgent.maybeCompactHistory` 自动压缩；`team-worker.md` 声明持久记忆并要求避免重复读取。
Multi-Agent 动态重规划：`AgentOrchestrator` 在 step 执行失败（Worker ERROR/空结果）或审查重试耗尽（`MAX_RETRIES_PER_STEP`）时回调 planner 重新规划剩余步骤；保留已完成步骤，新步骤 id 加 `r<n>_` 前缀避免冲突；`MAX_REPLAN_PER_RUN=2` 防 replan 风暴。规划阶段不再立即 `clearHistory`——保留 planner 上下文供 replan 复用，`run()` 结束时统一清理。同 issues 连续出现时走辩论收敛（`ReflectionService.isDebateConverged`），不触发 replan。
Multi-Agent Scatter-Gather：`ScatterGather.explore` 为同一目标派 N 路角度并行调研再 fan-in 合成（区别于无依赖 step 碰巧并行）；底层 `ParallelStep` + `WorkflowAdapters.fanInTask`。
Multi-Agent 增量辩论：审查拒绝后 Worker 收到 `buildIncrementalDebateContext`（只改指出的点，不推倒重来）；issues 实质相同或审查 JSON `converged: true` 时停止辩论并保留当前结果。
Multi-Agent 设计与 ablation：设计决策见 `docs/multi-agent-design.md`（四阶段迭代 + 权衡）；ablation 方法论见 `docs/multi-agent-ablation.md`，配套 `TeamBenchmark`（`src/test/java/com/bettercli/agent/TeamBenchmark.java`，`@EnabledIfSystemProperty` 默认禁用，`-Dbettercli.benchmark.enabled=true` 启用，跑单 Agent vs Multi baseline vs Multi full 三组对照，用 `CountingLlmClient` 包装器累计 token/调用次数，输出 `docs/multi-agent-ablation-results.md`）。

核心内置工具 20 个：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn` / `read_better_md` / `suggest_better_md` / `agent_memory_search` / `agent_memory_save` / `agent_memory_update` / `agent_memory_delete` / `session_search` / `update_plan` / `ask_user`

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP 配置会合并用户级 `~/.bettercli/mcp.json` 与项目级 `.bettercli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。
DeepSeek SSE 调用默认强制 HTTP/1.1，避免部分网络/网关下 HTTP/2 长流被远端重置成 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前按文本 provider 处理：`supportsImageInput()` 返回 false，历史或工具回灌里的图片 `ContentPart` 会在请求序列化时替换为文本提示，不能把 `image_url` block 发给 DeepSeek API。

讯飞星辰 MaaS provider 名为 `xfyun`，默认 Base URL 为 `https://maas-api.cn-huabei-1.xf-yun.com/v2`。`model` 必须使用服务管控页展示的 `modelId`；公开模型名 / Hugging Face 仓库名不一定可直接调用。微调模型用 `/config provider xfyun --lora-id <resourceId>` 配置服务卡片上的 resourceId，BetterCLI 会作为 HTTP header `lora_id` 发出。`xfyun` 当前按 MaaS 文档走纯对话请求，不向上游发送 BetterCLI 内置工具列表。
Agnes provider 名为 `agnes`，默认 Base URL 为 `https://apihub.agnes-ai.com/v1`，默认模型 `agnes-2.0-flash`，走 OpenAI-compatible Chat Completions，默认 1M context window，支持流式输出和 tools。

## 仓库结构

```
src/main/java/com/bettercli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java, AgentRole, RoleModelResolver, AgentBudget, AgentMessage, SubAgentResult, ReActPlan, PlanStore, SharedState, WorkflowScript, WorkflowStep, TaskStep, ParallelStep, ConditionalStep, LoopStep, WorkflowRuntime, WorkflowAdapters, ScatterGather, WorkflowCheckpoint, WorkflowCheckpointStore, DurableWorkflowBridge, Worker, MixedWorkerPool
├── a2a/         AgentCard, A2AClient, HttpTransport, JavaNetHttpTransport, RemoteAgent, A2AException
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, StepClient, KimiClient, FreeLlmApiClient, AgnesClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory, AgentMemoryStore, SqliteAgentMemoryStore, PostgresAgentMemoryStore, LongTermMemoryMigrator, MemoryMaintenanceScheduler, SessionMessageStore, SqliteSessionMessageStore, PostgresSessionMessageStore, SessionMessageIndexer, MemoryStoreFactory, MemoryMigrator
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── wechat/      iLink client, account store, message loop, non-interactive policy
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 **BETTER AGENT** ASCII 字标 + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- 界面语言默认中文（Banner tips、状态栏、Thinking、右提示）；`/lang zh|en` 可切换，并同步 system prompt 的 Language 策略。也可用 `BETTERCLI_UI_LANG` / `-Dbettercli.ui.lang` / `~/.bettercli/config.json` 的 `uiLanguage`。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。关键字段可用克制的 JLine `AttributedString` 彩色样式突出，但纯文本格式和宽度裁剪逻辑要保持稳定。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 conversationHistory、shortTermMemory、待注入 Skill buffer，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。`/compact` 会手动压缩当前 ReAct conversationHistory，不等待上下文阈值触发，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`BETTERCLI_MCP_STARTUP_WAIT_SECONDS` / `-Dbettercli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `BetterCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `BetterCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `BetterCliHistory` 持久化输入历史到 `~/.bettercli/history/input.history`；如果 `bettercli.history.file` / `BETTERCLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- 启动期会加载 `~/.bettercli/BETTER.md`、项目根 `BETTER.md`、项目根 `.bettercli/BETTER.md`、`BETTER.local.md`、`.bettercli/BETTER.local.md`，按此顺序注入 Project Context；`@relative/path.md` 可导入项目根内文件，总注入内容有字符预算，避免项目记忆变成 token 噪音。开启 `BETTERCLI_PAI_MD_RECURSIVE_DISCOVERY` 或 `-Dbettercli.better_md.recursive_discovery=true` 后，`ProjectMemoryLoader` 会从项目根向上递归查找祖先目录的 `BETTER.md`（对标 Claude Code CLAUDE.md 机制），按"从根到工作目录"顺序拼接。BETTER.md 主体字符上限默认 2200（`BETTERCLI_PAI_MD_MAX_CHARS` / `-Dbettercli.better_md.max_chars` 可调），超 80% 阈值时 `read_better_md` 会提示整合，超上限时 `suggest_better_md` 会拒绝追加。
- `read_better_md` 工具：Agent 主动读取当前已加载的 BETTER.md 完整内容 + 容量状态 + 已加载文件列表；`summary=true` 时只返回容量摘要。建议在 `suggest_better_md` 之前先调用，避免重复添加已有条目或超出容量上限。
- `suggest_better_md` 工具：Agent 向 BETTER.md 提议新条目，经 HITL 用户确认（批准 / 修改 / 拒绝 / 跳过）后追加到目标文件（默认项目根 `BETTER.md`，可通过 `target` 指定）。超容量上限时直接拒绝并提示先整合。属于中危写入操作，已纳入 `ApprovalPolicy.DANGEROUS_TOOLS`。
- Agent 维护的事实记忆（对标美团 1024 Agent `agent_memory` 表，与 `/save` 长期记忆分工不同）：
  - 存储：SQLite FTS5（`~/.bettercli/memory/agent_memory.db`），CRUD + BM25 全文检索 + confidence 加权（`final = -bm25 * (0.5 + confidence)`）+ user_vocabulary boost。
  - `agent_memory_save`：Agent 自主保存事实/模式/调试经验/工作流；`confidence < 0.7` 不应调用；敏感词（API key/密码/Bearer）会被拦截；`keywords` 必须是 3-8 个专有名词。
  - `agent_memory_search`：BM25 检索，按当前项目作用域过滤（PROJECT + GLOBAL 都可见）。
  - `agent_memory_update` / `agent_memory_delete`：更新/删除单条记忆。
  - 护栏：默认 1000 条上限（`BETTERCLI_MEMORY_MAX_ENTRIES` 可调），超限拒绝写入；`findSimilar` 基于 BM25 相似度（默认 0.85）自动去重；`MemoryMaintenanceScheduler` 后台定期清理 `expired` 状态条目。
  - 启动注入：`Agent.buildProjectMemoryContext()` 在 BETTER.md 之后追加 Agent 记忆摘要（前 50 条 / 10KB 硬上限），供 system prompt 引用。
  - 迁移：启动时 `LongTermMemoryMigrator` 自动从 `~/.bettercli/memory/long_term_memory.json` 迁移到 SQLite（`source=MIGRATED`，幂等，写 `.migrated-to-sqlite` 标记，不删原文件）。
  - CLI 命令：`/agent-memory`（或 `/am`）查看统计；`/agent-memory list` 列出条目；`/agent-memory search <关键词>` BM25 检索；`/agent-memory stats` 查看统计；`/agent-memory export` 导出为 JSON；`/agent-memory clear` 清空。用户只读视图，写入由 Agent 通过工具自主完成。
- 历史会话检索（对标美团 1024 Agent `session_messages` + `session_search`）：
  - 存储：SQLite FTS5（`~/.bettercli/memory/session_messages.db`），与 `agent_memory.db` 分开。
  - `session_search` 工具：BM25 全文检索 + 五阶段管道（检索 → 按 `conversation_id` 分组 → 加载完整会话 → 截断预览 → 返回）。默认当前项目作用域、回溯 30 天；可指定 `role_filter`（user/assistant）和 `days_back`（1-365）。
  - 异步索引：`SessionMessageIndexer` 在每轮 ReAct 结束后用独立线程池把新增消息增量索引到 SQLite，不阻塞主路径；跳过 `system` 消息和空内容；id = `conversationId-index` 保证幂等。
  - 迁移：启动时 `SqliteSessionMessageStore.migrateFromJsonl` 自动从 `~/.bettercli/history/session_*.jsonl` 迁移历史消息（幂等，写 `.session-messages-migrated` 标记，不删原文件）。
  - 会话 ID：每次 CLI 启动由 `SessionMessageIndexer.generateConversationId()` 生成新会话 ID。
- `/init` 会根据当前项目生成短 `BETTER.md`，只放 commands / project positioning / architecture / pitfalls / don'ts；默认不覆盖已有文件。
- `/export` 导出当前 ReAct `conversationHistory` 为 Markdown 到 `~/.bettercli/exports/session-*.md`；只支持无参数命令，包含完整 system prompt，便于检查 LLM 实际接收前的指令。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。
- ReAct 轻量规划（`update_plan` 工具，对标 Claude Code TodoWrite）：
 - 存储：`PlanStore`（Agent 实例字段，会话级内存态，不持久化）；`ReActPlan`（id / content / status: pending\|in_progress\|completed）。
 - `update_plan`：replace 语义，每次传完整任务列表（markdown checkbox 编码 `[ ]`/`[~]`/`[x]`，换行分隔），整体覆盖 store；空字符串清空。低危工具，不走 HITL 审批。
 - 触发：Agent system prompt 引导"多步骤复杂任务先用 `update_plan` 列步骤"，简单单步任务不强制。
 - 隔离：`update_plan` 不在 SubAgent 角色工具白名单内（阶段A 角色隔离），仅 ReAct 主 Agent 可用。
 - `/clear` 会同时清空 `planStore`。
- ReAct 工具失败反思（`ReflectionService`，对标 OpenHands CriticMixin + Claude Code 错误恢复反螺旋，阶段1 轻量版）：
 - 触发：`Agent` 在 `executeToolCalls` 之后检测本轮工具结果，若出现失败/策略拒绝/超时，由 `ReflectionService.buildReflectionPrompt` 构造反思提示，作为 user message 注入 `conversationHistory`，引导 LLM 复述错误原因 + 改换策略，不原样重试。不额外调用 LLM，零成本增量。
 - 分类：`ToolExecutionResult` 无显式 error 字段，`ReflectionService.classify` 基于字符串前缀（`🛡️ 策略拒绝` / `工具执行失败:` / `搜索失败` / `抓取失败` / `❌` / `*_失败`）+ `timedOut` 字段分类为 SUCCESS / FAILED / REJECTED / TIMEOUT。工具新增失败格式时同步更新前缀表。
 - 反螺旋：`ReflectionService` 内置 `consecutiveReflections` 计数器，连续反思超过 `maxConsecutive` 阈值（默认 2）后停止注入，交给 `AgentBudget` stagnation 检测兜底，避免"反思→失败→反思"死循环（借鉴 Claude Code `hasAttemptedReactiveCompact` one-shot 思路）。
 - 配置：`bettercli.react.reflection.enabled`（默认 true）/ `bettercli.react.reflection.max.consecutive`（默认 2）。
 - 隔离：仅 ReAct 主 Agent 启用，SubAgent 暂不做（角色隔离）。
 - 引导：`base.md` Reflection Policy 段落声明收到 `[反思提示]` 时的响应规范。
- Multi-Agent 共享黑板（`SharedState`，对标 2026 Blackboard 架构 + 状态所有权契约）：
 - 存储：`AgentOrchestrator` 每次 `run()` 重建；`goal`（orchestrator 写）/ `plan`（PLANNER 写）/ `artifacts.<stepId>`（WORKER 写）/ `reviews.<stepId>`（REVIEWER 写）/ `routingLog`（orchestrator 派活决策）。
 - 所有权契约：每个字段只允许特定角色写，越权抛 `SharedState.StateOwnershipException`（防 agent 互相覆盖产物）；所有角色可读。
 - 集成：`buildStepContext` 优先从黑板读 `artifacts`（回退到 `step.result()`）；worker/reviewer 产物双写进黑板（`step.result()` 仍保留供旧路径）；`pickWorker` / 并行批次把 routing 决策写入 `routingLog` 供审计。
 - 定位：阶段C 地基，后续阶段D（p2p 共享 task list）/ 阶段E（workflow 中间结果存黑板不灌 LLM context）复用此黑板。
- Multi-Agent peer-to-peer 留言（`ask_peer` 工具，对标 Claude Code agent teams：worker 间直接消息）：
 - 通道：`SharedState.peerMessages` + `postPeerMessage(from, to, content)` / `getInbox(workerName)`；空 `to` = 广播；inbox 排除自己发的。
 - 工具：`ask_peer`（WORKER 角色可用，PLANNER/REVIEWER 白名单不含）；异步单向留言，不阻塞不等回复（对标 2026 共识"p2p 难调试"，避免实时对话死锁）。
 - 暴露控制：`getToolDefinitions` 在 `sharedState` 未注入时过滤掉 `ask_peer`，避免主 ReAct 看到调用即失败的工具。
 - 集成：`AgentOrchestrator.runStep` 派活时 `setSharedState` + `setCurrentWorkerName`，并把 `buildInboxBlock` 注入 worker context，使 worker 执行前能看到同事留言。
- Dynamic Workflow（`WorkflowScript` + `WorkflowRuntime` + `WorkflowAdapters`，对标 Claude Code 2026.6 Dynamic Workflow：AI 写脚本编排 agent）：
 - 数据结构：`WorkflowScript(goal, steps)`；步骤 sealed interface `WorkflowStep`：`TaskStep`（顺序，action=Function<SharedState,String>）/ `ParallelStep`（并行 fan-out）/ `ConditionalStep`（读黑板 condition 走 then/else）/ `LoopStep`（循环到 condition 满足或 maxIterations）。
 - 执行器：`WorkflowRuntime.execute(script, state)` 脚本驱动；中间结果以 step id 为 key 存 `SharedState` 黑板（`putArtifactByRuntime`），不回灌 LLM context——对标"中间结果存脚本变量，主 session 只拿最终答案"，可扩到上千步不爆 context。
 - LLM 节点胶水：`WorkflowAdapters.subAgentAction` / `fanInAction` 把 `Worker#executeWithContext` 包成 `TaskStep.action`，使节点真正调 LLM；`fanInAction` 读黑板多个 artifact → 一次 LLM 合成（scatter-gather 的 gather 端）。便捷工厂：`llmTask` / `fanInTask`。
 - 断点续跑：`WorkflowCheckpoint` + `WorkflowCheckpointStore`；`WorkflowRuntime.withSkippedSteps` / `setCheckpointListener`；`DurableWorkflowBridge` 把 durable taskId 与 checkpoint 对齐，崩溃重入队后恢复黑板并跳过已完成步骤。`DurableTaskManager.setCompletionListener` 终态主动回推。
 - 控制流：顺序 / 并行 / 条件 / 循环；`LoopStep` 强制 maxIterations 硬上限防死循环（对标 2026 生产硬性最佳实践）。
 - 定位：与现有 Plan-and-Execute（静态 DAG）并存，是其"带控制流"的演进形态；TaskStep.action 可注入纯函数（单测）或 LLM 节点（生产）；实际使用时通过 WorkflowAdapters 把 SubAgent 注入。
- A2A 跨服务 agent（`a2a/` 包，对标 Google A2A 协议：agent↔agent，与 MCP 的 agent↔tool 互补）：
 - 发现：`AgentCard(name, description, url, skills)` 远程 agent 能力名片 + `hasSkill` 能力匹配。
 - 客户端：`A2AClient` JSON-RPC 2.0 over HTTP，`sendTask` / `getTask` / `executeAndWait`（轮询到终态，2 分钟硬上限防无限等待）；传输层 `HttpTransport` 可注入，生产用 `JavaNetHttpTransport`，测试用 mock。
 - 适配：`RemoteAgent` 包装 A2A 远程 agent 为本地可调用 worker，提供与 `SubAgent` 同签名的 `executeWithContext`，远程失败返回 `AgentMessage.error`。
 - 混编：`Worker` 接口让 `SubAgent` + `RemoteAgent` 共同实现；`MixedWorkerPool` 统一调度本地+远程 worker（名指派优先 / 游标轮询回退，与 orchestrator.pickWorker 对齐）。
 - 定位：本轮交付 A2A 客户端层 + 混编池（独立可测）；orchestrator worker 池从 `List<SubAgent>` 迁移到 `List<Worker>` 留作后续集成。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- `BETTER.md` 管团队共享的项目规则，长期记忆管个人或项目作用域的稳定事实；不要把一次性协作经验写进 `BETTER.md`
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear`
- Agent 维护的事实记忆（`agent_memory`）由 Agent 自主读写，不需要用户确认；`confidence < 0.7` 不应保存，敏感词（API key/密码/Bearer）会被拦截；默认 1000 条上限，超限拒绝写入；`findSimilar` 自动去重；`/agent-memory` 命令组提供用户只读视图（list/search/stats/export/clear）
- 两道压缩不要混淆：shortTermMemory 压缩 vs conversationHistory 压缩（后者是防 window 超限的关键）
- 自动压缩阈值按 Claude Code 风格预留摘要输出和安全缓冲：大窗口使用 `window - 20k - 13k`，例如 200k 窗口约 167k 触发、1M 窗口约 967k 触发；小窗口按比例缩小预留。

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线
- 微信 iLink 通道没有人工审批面板，必须走非交互式默认拒绝策略：只读工具默认允许，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝，文件写入仍由 PathGuard 限定在绑定 workspace 内。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区，用于相对日期理解；联网搜索不再由 prompt 的 Freshness Policy 强制，是否调用 `web_search` 交给模型基于工具 schema 和用户目标自主决定。
- “当前项目/当前 README/当前文件/当前代码”等表达属于本地上下文任务，通常应由模型选择 `glob_files` / `grep_code` / `read_file`，而不是联网工具。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `AgentMemoryStore` + `SqliteAgentMemoryStore` + `LongTermMemoryMigrator` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| Multi-Agent 动态重规划 | `mvn test -Dtest=ReplanIntegrationTest` |
| Multi-Agent Scatter-Gather / 辩论收敛 | `mvn test -Dtest=ScatterGatherTest,DebateConvergenceIntegrationTest,ReflectionServiceTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| Agent 记忆 | `mvn test -Dtest=AgentMemoryEntryTest,MemoryQueryModelsTest,SqliteAgentMemoryStoreTest,MemoryMaintenanceSchedulerTest,LongTermMemoryMigratorTest` |
| 会话历史检索 | `mvn test -Dtest=SessionMessageModelsTest,SqliteSessionMessageStoreTest,SessionMessageIndexerTest` |
| ReAct 轻量规划 | `mvn test -Dtest=PlanStoreTest,UpdatePlanToolTest,AgentUpdatePlanIntegrationTest` |
| ReAct 工具失败反思 | `mvn test -Dtest=ReflectionServiceTest,AgentReflectionIntegrationTest` |
| Multi-Agent 共享黑板 | `mvn test -Dtest=SharedStateTest,AgentSharedStateIntegrationTest` |
| Multi-Agent p2p 留言 | `mvn test -Dtest=AskPeerToolTest,SharedStateTest` |
| Dynamic Workflow | `mvn test -Dtest=WorkflowRuntimeTest,WorkflowLlmNodeTest` |
| Durable Workflow 断点续跑 | `mvn test -Dtest=DurableWorkflowResumeTest,DurableTaskManagerTest` |
| A2A 跨服务 agent | `mvn test -Dtest=AgentCardTest,A2AClientTest,RemoteAgentTest,MixedWorkerPoolTest` |
| 可插拔后端 | `mvn test -Dtest=MemoryStoreFactoryTest,PostgresMemoryStoresTest` |
| Agent 任务级 Eval | `mvn test -Dtest=DeterministicScorerTest,EvalRunnerTest` |
| ask_user 主动反问 | `mvn test -Dtest=AskUserToolTest,TerminalHitlHandlerTest` |
| 工具可操作错误返回 | `mvn test -Dtest=ActionableToolErrorTest,ReflectionServiceTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 迭代规划 | docs/iteration-roadmap.md（多 LLM 协作深化 → 异步跨端运行时主线，求职项目方向；上下文压缩后找回规划的权威来源） |
| 工具调用 | ToolRegistry.java + Agent.java |
| ReAct 轻量规划 | agent/PlanStore.java + agent/ReActPlan.java + ToolRegistry.java (`update_plan`) |
| ReAct 工具失败反思 | agent/ReflectionService.java + Agent.java (`maybeInjectReflection`) |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Agent 记忆 | memory/AgentMemoryStore.java + SqliteAgentMemoryStore.java + LongTermMemoryMigrator.java |
| 会话历史检索 | memory/SessionMessageStore.java + SqliteSessionMessageStore.java + SessionMessageIndexer.java |
| 可插拔后端 | memory/MemoryStoreFactory.java + PostgresAgentMemoryStore.java + PostgresSessionMessageStore.java + MemoryMigrator.java |
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| Multi-Agent 动态重规划 | AgentOrchestrator.java（`triggerReplan` / `StepOutcome`） |
| Multi-Agent Scatter-Gather | agent/ScatterGather.java + WorkflowAdapters.fanInTask |
| Multi-Agent 共享黑板 | agent/SharedState.java + AgentOrchestrator.java (`buildStepContext` / `pickWorker` / 产物双写) |
| Dynamic Workflow | agent/WorkflowScript.java + WorkflowStep.java + WorkflowRuntime.java + WorkflowAdapters.java |
| Durable Workflow 断点续跑 | agent/DurableWorkflowBridge.java + WorkflowCheckpointStore + runtime/task/DurableTaskManager |
| A2A 跨服务 agent | a2a/AgentCard.java + A2AClient.java + RemoteAgent.java + agent/Worker.java + agent/MixedWorkerPool.java |
| Agent 任务级 Eval | eval/EvalRunner.java + eval/DeterministicScorer.java + evals/golden-tasks.jsonl |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启 / PostgreSQL 记忆后端（骨架已就位，需要 JDBC 驱动 + 云端配置）/ SQLite→PostgreSQL 迁移工具（骨架已就位）

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
