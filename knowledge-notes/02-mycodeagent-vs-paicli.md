# MyCodeAgent vs PAICLI（BetterCLI）深度对比

> 这是一篇基于"把两个项目的模块一个个读下来"的对比帖，不是看行数。
> MyCodeAgent：Python，~162 个 py 文件，单 Agent 循环的工程化范本。
> PAICLI/BetterCLI：Java，229 个主文件 / 33095 行，对标 Claude Code 的功能矩阵。

## 一、定位与设计哲学

**MyCodeAgent** 走的是"精简但工程化极强"路线。它的 `runtime/loop.py` 是一个显式状态机：`LoopState` + `TransitionReason` + `RuntimeEvent` 全链路埋点，每一步状态转移都有 reason 和 trace。它把"单 Agent 单循环"这件事做到了极致——一个 `RuntimeRunner`，没有 Plan-and-Execute，没有 Multi-Agent 编排（`Task` 工具只是子会话委托，不是 DAG）。

**PAICLI** 走的是"功能广度优先"路线。三条执行路径并存：`Agent`（ReAct）、`PlanExecuteAgent`（DAG 调度）、`AgentOrchestrator`（主从 Multi-Agent）。已交付 23 期，对标 Claude Code 的功能矩阵。

一句话：**MyCodeAgent 是"把一件事做到工业级"，PAICLI 是"把一整套能力铺开"**。

## 二、核心循环

| 维度 | MyCodeAgent `loop.py` | PAICLI `Agent.java` |
|---|---|---|
| 循环结构 | 显式状态机 + for-step + 内层 while 重试 | while(true) + budget 保险阀 |
| 退出主导权 | completion gate + max_steps | LLM 自己决定，budget 只兜底 |
| 死循环防护 | max_steps 硬上限 | AgentBudget 三道阀：token预算(默认无限)+停滞检测(连续3轮相同签名)+50轮硬上限 |
| 模型错误处理 | 分类+恢复：PROMPT_TOO_LONG→reactive_compact、EMPTY→retry_with_hint | try-catch 返回错误字符串 |
| 可观测性 | RuntimeEvent 全链路埋点 | SLF4J 日志 + 状态栏 |

这块 **MyCodeAgent 明显更精细**。它的 completion gate（`DeterministicCompletionVerifier`）会推断"完成要求"、收集"验证证据"、再判定 PASS/UNVERIFIED/BLOCK——不通过就追加 feedback 让 LLM 重试。PAICLI 的 ReAct 是"LLM 说完了就完了"，没有完成度校验这一层。MyCodeAgent 的模型错误分类恢复也是 PAICLI 没有的——PROMPT_TOO_LONG 时会触发 `reactive_compact` 压缩历史再重试。

PAICLI 的优势在 `AgentBudget` 的"停滞检测"——连续 3 轮用完全相同的工具名+参数就判定死循环，这个 MyCodeAgent 没有。

## 三、规划与多 Agent

**MyCodeAgent**：没有真正的 DAG 规划。`Task` 工具是子会话委托，`TodoWrite` 是内部规划状态工具，都不是调度器。

**PAICLI**：这是它的重头。
- `PlanExecuteAgent` + `ExecutionPlan`：真正的 DAG——拓扑排序、环检测、`getExecutionBatches` 分批、同批次无依赖任务并行（固定线程池+缓冲输出按顺序 flush 防交错）、失败重规划（进度<50% 时 `planner.replan`）、Plan 审阅交互（EXECUTE/SUPPLEMENT/CANCEL，用户可补充要求重规划）。
- `AgentOrchestrator`：主从架构（planner + 2 worker + reviewer），并行批次用 `BlockingQueue` 池化 worker 避免同一 worker 被并发占用、每步创建独立 reviewer 实例避免对话历史竞争、assignee 路由（规划者指派 + 幻觉 worker 名归一化防路由失败）、reviewer 审查不通过最多重试 2 次、解析失败保守判不通过。

**这块 PAICLI 完胜**，没有可比性。MyCodeAgent 在这块是空白。

## 四、工具与权限

**MyCodeAgent `permissions.py`**：三态决策 ALLOW/ASK/DENY + 风险分级（LOW/MEDIUM/HIGH/UNKNOWN）+ `readonly_subagent` 边界。Bash 有 deny 正则（sudo/rm/git checkout/git reset/嵌套 shell/管道 sh/命令替换）、ask 正则（mv/cp/重定向/npm install/pip install/chmod）、low-risk 白名单（pwd/git status/git diff/git log/sed -n）。**未知工具 fail-closed**（DENY）。

**PAICLI**：
- `PathGuard`：真正的"围栏"——处理 macOS `/var→/private` 符号链接、不存在的路径向上找祖先解析 realPath 再接回（write_file 新文件场景）、symlink 逃逸。设计很扎实。
- `CommandGuard`：黑名单 fast-fail，明确自我定位为"辅助而非主防线"。有 Windows 命令规则（del/format/diskpart/Remove-Item）。
- `HitlToolRegistry`：装饰器模式继承 `ToolRegistry` 覆写 `executeToolOutput`，未启用零开销，支持 approve-all-by-tool/server、参数修改、audit 记录。拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard。

**对比**：MyCodeAgent 的权限是"决策框架"（三态+风险分级+readonly 边界），更系统；PAICLI 是"围栏+黑名单+HITL 装饰器"三层，更具体。PAICLI 的 PathGuard 处理 symlink/不存在路径的细节更深，MyCodeAgent 的风险分级和 readonly_subagent 边界更成体系。**这块大致打平，路线不同**。

## 五、记忆系统

**MyCodeAgent**：`context_engine` + `history_manager` + `context_compaction`。有 `compact_if_needed` 主动压缩、`reactive_compact` 反应式压缩、`build_model_view` 投影。会话记忆 + 摘要压缩。

**PAICLI**：记忆是它的重头戏，分四层：
- `MemoryManager` 门面：短期（`ConversationMemory`）+ 长期（`LongTermMemory`）+ 压缩（`ContextCompressor`）+ 检索（`MemoryRetriever`），项目作用域 key 用 realPath。
- `ConversationHistoryCompactor`：调 LLM 前压缩 conversationHistory（这才是决定下一轮 input token 的关键），分割点落在 user 边界，保留最近 1 个 user 轮次和 tool_call/tool_result 边界。
- `SqliteAgentMemoryStore`：SQLite FTS5 BM25 + confidence 加权（`final = -bm25 * (0.5 + confidence)`）+ user_vocabulary boost + 1000 条容量护栏 + BM25 相似度自动去重 + TTL 清理 + json 迁移。对标美团 1024 Agent `agent_memory` 表。
- `SessionMessageStore` + `SessionMessageIndexer`：会话消息异步索引到独立 SQLite，`session_search` 五阶段管道（检索→按 conversation_id 分组→加载完整会话→截断预览→返回）。

**这块 PAICLI 明显更重更完整**。MyCodeAgent 的压缩是"够用"，PAICLI 是"对标美团 1024 Agent 的完整记忆体系"。但 MyCodeAgent 的 `reactive_compact`（PROMPT_TOO_LONG 时反应式压缩）是 PAICLI 没有的精细化能力。

## 六、LLM 抽象

**MyCodeAgent**：`core/llm` + OpenAI 兼容 transport，有 `extract_reasoning_content`/`extract_tool_calls`/`classify_model_error`。

**PAICLI**：`LlmClient` 接口（chat 带/不带 stream listener、能力探测 supportsTools/ImageInput/PromptCaching、Message record 支持 contentParts/reasoningContent/toolCalls）+ 7 个 provider（GLM/DeepSeek/Step/Kimi/FreeLlmApi/Xfyun/Agnes）+ `LlmClientFactory` 别名归一化。`DeepSeekClient` 有针对性适配：强制 HTTP/1.1 避免 HTTP/2 长流被重置、reasoning_content 回带历史、不支持图片、1M context、prompt cache。`AbstractOpenAiCompatibleClient` 共享 HTTP 客户端配置。

**这块 PAICLI 更成熟**——多 provider 工厂 + 别名归一化 + per-provider 适配（DeepSeek 的 HTTP/1.1 是踩过坑的）。MyCodeAgent 更偏"OpenAI 兼容单线 + 错误分类"。

## 七、MCP

**MyCodeAgent**：有 `test_mcp_protocol.py`、`extensions/test_mcp_extension.py`，MCP 作为扩展存在。

**PAICLI**：`McpServerManager` 是企业级实现——并发启动（专属 daemon executor 避免占满 commonPool、maxWait 超时不阻塞首屏后台继续）、stdio/http 双 transport、resources 虚拟工具、`NotificationRouter`（tools/list_changed、resources/updated 增量失效）、`McpResourceCache`、audit 集成、重复工具名校验、`${VAR}` 环境变量展开。

**这块 PAICLI 完胜**，是真正生产级的 MCP 集成。

## 八、其他维度

- **Prompt**：PAICLI 有分层模板系统（`PromptAssembler`：base + personality + mode + approvals + runtime + project context + skills，从资源文件加载，变量替换，tools 禁用裁剪，语言段校验）。MyCodeAgent 有 `context_builder` + `prompt_assembly_trace`。PAICLI 更结构化。
- **渲染**：PAICLI 有 `InlineRenderer`（JLine inline TUI，不进 alternate screen，scroll region 底部状态栏，代码块折叠状态机，transcript 锁）+ `StreamRenderer`（reasoning_content 流式分区，处理服务器把思考切段甚至追加在 content 之后的情况——这是踩过坑的精细设计）。MyCodeAgent 是 `ui_components` + console 输出。**PAICLI 的终端体验明显更重**。
- **快照**：PAICLI 有 `SnapshotService`（side-git 每轮前后快照、post-turn 异步写入、restore、awaitIdle）。MyCodeAgent 没有。
- **RAG**：PAICLI 有 `CodeRetriever`（语义+关键词混合检索，embedding+BM25+类型加分）。MyCodeAgent 有 `search_code` 工具。PAICLI 更完整。
- **测试**：MyCodeAgent 测试非常充分（contracts/scenarios/runtime/extensions/evals 多层，有 `protocol_validator`、`test_tool_registry_schema_compat`、`test_release_metrics`）。PAICLI 有 131 个测试文件、phase 化 smoke 测试。**MyCodeAgent 的测试工程化更系统**（contract 测试、scenario 测试、release metrics 检查）。

## 九、综合打分（10 分制）

| 维度 | MyCodeAgent | PAICLI | 说明 |
|---|---|---|---|
| 核心循环精细度 | **8.5** | 7.0 | MCA 状态机+错误恢复+completion gate 更精细；PAICLI 停滞检测是亮点 |
| 规划/多 Agent | 3.0 | **9.0** | MCA 几乎空白；PAICLI DAG+主从编排完整 |
| 工具与权限 | 7.5 | **8.0** | 路线不同，PAICLI PathGuard+HITL 更深，MCA 决策框架更系统 |
| 记忆系统 | 6.5 | **9.0** | PAICLI 四层+SQLite FTS5+会话索引；MCA 够用但薄 |
| LLM 抽象 | 6.5 | **8.5** | PAICLI 7 provider+per-provider 适配更成熟 |
| MCP | 5.0 | **9.0** | PAICLI 企业级；MCA 仅扩展 |
| 终端体验 | 5.5 | **8.5** | PAICLI inline TUI+流式分区更重 |
| 可观测性 | **8.5** | 7.0 | MCA RuntimeEvent 全链路埋点更系统 |
| 测试工程化 | **8.5** | 7.5 | MCA contract/scenario/release metrics 更成体系 |
| 工程成熟度 | **8.0** | 7.5 | MCA pyproject.toml+lean design 更克制；PAICLI 功能广但杂 |
| 功能广度 | 5.0 | **9.5** | MCA 单循环；PAICLI 23 期全铺开 |

## 十、结论

**没有谁更好，只有谁更适合什么场景**：

- **MyCodeAgent** 是"单 Agent 循环的工程化范本"——如果你要学"怎么把一个 ReAct 循环做到工业级"（状态机、错误恢复、completion gate、全链路埋点、contract 测试），它更值得读。代码克制、测试系统、设计哲学清晰（lean）。短板是功能窄：无规划、无多 Agent、MCP 薄、记忆薄。

- **PAICLI** 是"Claude Code 功能矩阵的开源实现"——如果你要学"一个商业 Code Agent CLI 该有哪些能力"（DAG 规划、Multi-Agent、四层记忆、MCP 企业集成、inline TUI、side-git 快照、多 provider），它更值得读。短板是杂：功能堆叠多但部分模块精细度不如 MCA（如循环错误恢复、completion 校验、可观测性埋点）。

**一句话总结**：MyCodeAgent 像一把磨得极锋利的单刃刀，PAICLI 像一个装满工具的瑞士军刀。

## 附：本次实际读过的关键模块

**PAICLI**：`Agent.java`、`PlanExecuteAgent.java`、`AgentOrchestrator.java`、`AgentBudget.java`、`ExecutionPlan.java`、`LlmClient.java`、`LlmClientFactory.java`、`DeepSeekClient.java`、`MemoryManager.java`、`SqliteAgentMemoryStore.java`、`HitlToolRegistry.java`、`PathGuard.java`、`CommandGuard.java`、`PromptAssembler.java`、`McpServerManager.java`、`SnapshotService.java`、`InlineRenderer.java`、`CodeRetriever.java`、`WebFetcher.java`。

**MyCodeAgent**：`runtime/loop.py`、`tools/permissions.py`（外加 glob 全量 162 个 py 文件结构确认）。

---

## 修正与补充（第二轮自读后）

> 上一版对比有几处判断是基于旧 subagent 报告，我自己把 MyCodeAgent 的工具层、LLM 层、上下文引擎、completion gate、子 Agent、文件工作区都读透后，发现需要修正。下面是纠正。

### 修正 1：MyCodeAgent 不是"无多 Agent"，有受限子 Agent + 双重校验

`runtime/subagents.py` 有完整的受限子 Agent 系统：
- `RuntimeProfile`（frozen dataclass）：`explore` 和 `verification` 两个 profile，**强制 read-only allowlist**（只允许 Glob/Grep/Read）、**禁止递归**（`recursive_subagents` 或 allowlist 含 Task 直接抛错）、**禁止 Edit/Bash**。
- `SubagentLauncher`：每个子 Agent 跑独立 child trace、独立 history、独立 context engine、`readonly_subagent` 权限模式，可选用 light 模型省 token。
- **`SubagentCompletionVerifier`**：先跑确定性校验，通过后再派一个 verification 子 Agent **独立复核**主 Agent 的完成候选——这是"用另一个 LLM 调用验证主 LLM"的双 Agent 校验。

所以正确说法是：**MCA 有"主 Agent + explore/verify 子 Agent + 双重完成校验"，但无 DAG 规划、无并行批次调度**。PAICLI 有 DAG + 主从编排 + 并行批次，但完成校验是确定性单层。**两者是多 Agent 的不同路线**：MCA 重"校验隔离"，PAICLI 重"任务并行"。

### 修正 2：MyCodeAgent LLM 层比 PAICLI 强，不是弱

`core/llm.py` 的 `HelloAgentsLLM`：
- **11 个 provider profile**（openai/deepseek/qwen/modelscope/kimi/zhipu/siliconflow/ollama/vllm/local/auto），比 PAICLI 的 7 个多。
- **provider 自动探测**（按 detect_envs + url_markers，多命中报错要求显式指定）——PAICLI 没有。
- **重试 + 指数退避**（`_invoke_with_retries`，`max_retries` + `2^attempt` 退避）——**PAICLI 完全没有重试**，失败直接抛。
- **provider 兼容适配**（minimax 后端 n=1、去 tool_choice；多 system 消息合并）。
- **temperature 策略**（Kimi K2 强制 temperature=1 并告警）。

之前说"PAICLI LLM 更成熟"是错的。**LLM 弹性调用这块 MCA 明显更强**（重试退避 + 自动探测），PAICLI 的优势仅在 per-provider 的 HTTP 适配（DeepSeek HTTP/1.1、reasoning 回灌）更细。

### 修正 3：MyCodeAgent 工具层有 PAICLI 没有的几样东西

- **CircuitBreaker 熔断器**（`tools/circuit_breaker.py`）：每工具失败计数，连续 3 次失败 OPEN、300s 后 HALF_OPEN 探活，OPEN 时从 tools schema 里隐藏该工具。**PAICLI 的工具和 LLM 都没有熔断**。
- **乐观锁原子写**（`tools/workspace.py` 的 `FileWorkspace`）：`atomic_write` 用 `FileSnapshot`(mtime_ns+size) 校验未并发改、`mkstemp`+`os.replace`+`fsync` 原子替换并保留权限；`atomic_create` 用 `os.link` 防并发创建；`read_text` 前后双 snapshot 防读时被改；创建后 re-resolve 防 raced symlink 逃逸。**PAICLI 的 write_file 没有乐观锁、没有原子写、没有 fsync**——这块 MCA 明显更扎实。
- **结果预算双层**（`tools/orchestrator.py`）：单工具 50KB + 整消息 200KB，超限 `force_truncate_result` 压缩并原文落盘 `full_output_path`，按"最大优先"压缩到达标。PAICLI 没有这种消息级预算。
- **并发安全分区**：`SAFE={Read,Grep,Glob}` 并行、`UNSAFE={Edit,Bash,Task,Skill,TodoWrite}` 串行，相邻 safe 合批。PAICLI 是"最多 4 并发 invokeAll"不区分工具是否并发安全。
- **Bash 黑名单更细**：强制 ls→Glob、cat→Read、grep→Grep（逼 LLM 用结构化工具而非 shell 读写）、交互式命令、网络工具默认禁用、cd 越界检查。比 PAICLI 的 CommandGuard 细。但跨平台弱（`subprocess.run(shell=True)` 没显式 cmd/bash 分流，PAICLI 有 `cmd.exe /c` vs `bash -c`）。

### 修正 4：Completion gate 的"证据失效"机制

`runtime/completion.py` 的 `DeterministicCompletionVerifier` 有个 PAICLI 完全没有的精巧设计：**验证证据失效**——如果 `Edit`（mutating tool）在验证命令（pytest/lint/build）之后发生，就把之前的 evidence 标记为 `modified_after_verification` 失效。这是防"先跑测试通过、再改代码、然后谎称测试通过"的硬设计。加上 `infer_completion_requirements` 从用户输入正则推断需要验证什么（中英文模式）+ TodoWrite 未完成项检查，FAIL 时生成 blocking_feedback 注入回 LLM 继续。**这是 MCA 最独特的"防 LLM 谎报完成"能力**。

### 修正后的打分调整

| 维度 | 旧 MCA | 修正 MCA | 旧 PAICLI | 修正 PAICLI | 修正说明 |
|---|---|---|---|---|---|
| 核心循环精细度 | 8.5 | **8.5** | 7.0 | 7.0 | 不变，MCA 仍领先（状态机+错误恢复+completion gate+证据失效） |
| 规划/多 Agent | 3.0 | **5.5** | 9.0 | **8.5** | MCA 有子 Agent+双重校验（非空白）；PAICLI DAG 仍在，但完成校验单层 |
| 工具与权限 | 7.5 | **8.5** | 8.0 | 7.5 | MCA 熔断+乐观锁+原子写+结果预算+并发分区，反超；PAICLI 跨平台分流仍强 |
| 记忆系统 | 6.5 | 6.5 | 9.0 | 9.0 | 不变，PAICLI 四层+FTS5 仍领先；MCA 有 session_memory 但无持久 FTS |
| LLM 抽象 | 6.5 | **8.0** | 8.5 | **7.5** | 反转：MCA 11 provider+重试退避+自动探测更强；PAICLI 仅 per-provider HTTP 适配细 |
| MCP | 5.0 | 5.0 | 9.0 | 9.0 | 不变，PAICLI 企业级 |
| 终端体验 | 5.5 | 5.5 | 8.5 | 8.5 | 不变，PAICLI inline TUI 重 |
| 可观测性 | 8.5 | 8.5 | 7.0 | 7.0 | 不变，MCA RuntimeEvent 全链路埋点 |
| 测试工程化 | 8.5 | 8.5 | 7.5 | 7.5 | 不变 |
| 工程成熟度 | 8.0 | **8.5** | 7.5 | 7.5 | MCA 乐观锁+熔断+原子写加分 |
| 功能广度 | 5.0 | 5.0 | 9.5 | 9.5 | 不变 |

### 修正后的结论

两个项目的差距**比我第一版判断的小**。MCA 在"工具层工程化"（熔断、乐观锁、原子写、结果预算、并发分区）和"LLM 弹性"（重试退避、自动探测）上**反超** PAICLI，不是单纯"功能窄"。PAICLI 的优势集中在 PAICLI 独有的能力面：DAG 规划、主从 Multi-Agent 并行、四层持久记忆、MCP 企业集成、inline TUI、side-git 快照。

**修正后一句话**：MCA 是"把单 Agent 循环 + 工具底座做到工业级"的范本（循环精细度、工具工程化、LLM 弹性、完成校验都强）；PAICLI 是"把 Agent 能力面铺到 Claude Code 级别"的实现（广度强，但工具层和循环精细度不如 MCA）。**选 MCA 学"怎么把底座做扎实"，选 PAICLI 学"一个商业 Agent 还能有哪些能力面"**。

### 第二轮自读的 MyCodeAgent 模块

`tools/registry.py`、`tools/orchestrator.py`、`tools/circuit_breaker.py`、`tools/workspace.py`、`tools/builtin/bash.py`、`core/llm.py`、`runtime/completion.py`、`runtime/context/engine.py`、`runtime/state.py`、`runtime/subagents.py`。
