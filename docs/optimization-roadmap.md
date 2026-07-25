# PaiCLI 优化方向与迭代计划

本文是 PaiCLI 所有后续迭代计划的**唯一入口**，涵盖两类内容：

* **工程负债收敛**（第二部分）：SDK 化、RAG 现代化、Eval 闭环、测试与可靠性、MCP 高级能力等近期可落地项。
* **架构前沿对标**（第三部分）：推理循环 / 多 Agent 协作 / 上下文管理 / 记忆系统 / 安全审计 / 代码 RAG 六个核心维度，逐维度做「实现现状 → 业界前沿(2025-2026) → 差距 → 最适合本项目的方向」深度分析。

贯穿全文的原则：**只能在真正提升可靠性/可讲性、且不推翻现有架构骨架的地方动手；不为追新而追新。底座用轮子，内核自己造。** 优化方向标注 🟢 优先 / 🟡 次优 / ⚪ 了解即可。现状均基于代码实读，前沿基于 Anthropic 官方工程博客、Mem0/CodeRAG 等论文与主流框架实践。

信息优先级仍以代码实际行为为准（见 `AGENTS.md`）。本文是方向清单，不是已交付承诺。

## 第一部分 · 优化优先级总览（跨维度落地顺序）

按“对本项目性价比 + 可讲性 + 不破坏架构”综合排序，建议落地顺序：

1. 🟢 **Agent 端到端 Eval 闭环**（第二部分 §3）——是验证下面所有改动是否真正有效的前提。
2. 🟢 **RAG 混合检索 RRF + rerank + ANN 索引**（第二部分 §2 / 第三部分维度六）——硬短板，且记忆语义检索可共享底座（维度四）。
3. 🟡 **上下文 structured note-taking + 压缩失败兜底**（维度三）——补齐 Anthropic 四策略唯一缺失项。
4. 🟢 **主动反问用户 `ask_user` (clarification/elicitation)**（维度一 §1.z）——真空白，复用现有 HITL 基础设施成本极低，对可靠性提升大，性价比最高。
5. 🟢 **信号触发的定向反思回灌**（维度一 §1.y）——低成本提成功率，且明确不做全局每步自评。
6. 🟡 **多 Agent worker 专精化 + reviewer fail-safe**（维度二）。
7. 🟡 **安全脱敏补全 + MCP 细粒度权限 +（可选）轻量进程隔离**（维度五）。
8. 🟢 **（结构性重构，需在 Eval 之后）模式统一为单入口，plan / team 工具化**（维度一 §1.x）——产品心智对标 Claude Code 的关键一步，改动面较大且必须靠 Eval 验证“自主决策 vs 用户手选”的优劣，故排在有 Eval 数据支撑之后启动。

每一项落地后都应回到 Eval 闭环量化“改动前后成功率/延迟/成本”的变化——这既是工程闭环，也是最有说服力的简历素材。

## 第二部分 · 工程负债收敛与近期落地项

### 1. 用 SDK 减轻工程负债

原则：**底座用轮子，内核自己造。** 在“不产生差异化价值、且有成熟标准封装”的地方，手写是负债；在“协议非标”或“是核心竞争力”的地方，手写才是资产。当前 LLM 调用层在底座这一块手写偏多，是优先收敛对象。

#### 1.1 OpenAI-compatible provider 迁移到官方 SDK

* **现状：** `AbstractOpenAICompatibleClient` 用 OkHttp 手写了 SSE 流式解析、`tool_call` 分片累积、错误体解析、超时调优等；GLM / DeepSeek / Kimi / Step / Agnes / FreeLLMAPI 均基于它。
* **问题：** SSE 解析和 `tool_call` streaming 累积是最容易出 bug、最不产生差异化价值的部分，属于重复造轮子。
* **方向：** 把标准 `/chat/completions` 协议的 provider 迁到官方 `com.openai:openai-java`（支持自定义 baseUrl + key），白嫖其重试、超时、类型安全和跟随 API 更新的能力，每个 client 预计从数百行收敛到数十行。

#### 1.2 保留 provider 差异适配层（不要一刀切上 SDK）

* **动机：** 国内模型常是“假兼容”——GLM 5.1 走 Coding endpoint、5v-turbo 走多模态 endpoint；DeepSeek V4 / Kimi thinking 的 `reasoning_content` 必须回灌（SDK 数据模型无此字段）；DeepSeek 需强制 HTTP/1.1；讯飞星辰要发 `lora_id` 非标 header。
* **方向：** 迁 SDK 后，仍以钩子/模板方式保留 provider 差异层，承接这些非标字段；避免因 SDK 强类型封装而无法注入非标参数，最后被迫 fork SDK 或绕回手写。

#### 1.3 其它底层的轮子化评估

* **动机：** LLM HTTP 底层之外，仍有可用成熟库替代的手写点值得逐个评估（如流式解析、重试退避、限流等通用能力）。
* **方向：** 逐项判断“是否产生差异化价值”，只对纯底层能力做替换，不动 Agent 编排内核。

### 2. RAG 检索现代化（硬短板）

* **现状：** `VectorStore` 把向量以 JSON 数组存 SQLite，检索时全部拉进内存逐个算余弦相似度，O(n) 全表扫描，无 ANN 索引。
* **问题：** 招聘市场对 RAG 近乎 100% 提及，且要求“混合检索 + Rerank + 引用”，当前方案偏原始。
* **方向：** 迁移到 ANN 索引（HNSW / IVF）+ 向量与 BM25 混合检索 + Rerank + 可核验引用；保留现有 RAG 分层抽象，仅替换检索实现。详细的现状/前沿/差距分析见第三部分维度六。

### 3. Agent 端到端评测闭环（最高性价比）

* **现状：** 测试覆盖面广（100+ 测试文件），但 Eval 仅有 `CodeSearchGoldenSetTest` 一个工具级 golden set，缺 Agent 任务级评测。
* **问题：** 无法量化“改一版 prompt / 换编排模式后成功率、延迟、成本如何变化”，也拿不到简历所需的量化数字。
* **目标：** 把 Agent 从“能跑”推进到“有数据证明它在什么边界内可靠、超出边界怎么退化”。

#### 3.1 决策：自研轻量 harness，不引入外部框架

* **结论：** 自己写，不引入外部 Eval 框架。
* **原因：**
  * 成熟框架（`DeepEval` / `Ragas` / `promptfoo` / `OpenAI Evals`）几乎全是 Python 生态，PaiCLI 是纯 Java 项目；为 Eval 硬塞 Python 需要起子进程跨语言调用或重实现 Agent 逻辑，胶水成本远大于自研。
  * Eval 核心逻辑（喂任务 → 跑 Agent → 判定 → 出报告）本身不复杂，且可直接复用现成的 `Agent` / `PlanExecuteAgent` / `AgentOrchestrator`，自研最贴合。
  * 符合本文原则：底座轮子化的前提是“同生态且省事”，跨语言硬塞不省事，则自研。
* **边界：** LLM-as-judge 只是再调一次本项目已有的 `LlmClient`，不需要额外框架。

#### 3.2 落地方案（放在 `evals/` 目录，作为独立入口，不污染主流流程）

* **黄金任务集** `evals/golden-tasks.jsonl`：每条含 `id` / `mode`（react|plan|team）/ `input` / `success`（可判定成功标准；如文件存在、内容 grep、命令退出码）。首批 20-30 条，覆盖简单、多步、需工具、易失败四类。
* **Runner：** 读取任务集，对每条按指定 mode 复用现有 Agent 入口执行，收集：任务是否成功、工具调用成功率、迭代轮数、输入/输出 Token、耗时、成本估算（复用 `TokenUsageFormatter`）。跑在临时工作目录，避免污染真实项目；受 `PathGuard` 约束。
* **判分器** `scorers/`：优先**确定性判分**（文件/内容/退出码），开放式任务再用 **LLM-as-judge**（复用 `LlmClient` 出裁判打分，并记录裁判本身的不确定性）。
* **离线回放** `replay/`：录制每次运行的 LLM 响应快照；重构编排器时录制响应重放（不真调 LLM，省钱省时），做回归门禁，拦截逻辑改动导致的质量回退。
* **对比报告** `report.md`：输出成功率 / 延迟(P50/P95) / Token / 成本对比表，支持“本版 vs 上版”差异，直接产出简历所需量化数字。

#### 3.3 分阶段推进

1. **最小闭环：** 5-8 条任务 + Runner 跑通三种编排模式 + 确定性判分 + 一张对比表（先能回答“哪种模式成功率最高”）。
2. **加回放：** 录制/重放 LLM 响应，接入回归门禁。
3. **补 LLM-as-judge：** 覆盖开放式任务，形成完整体系。

### 4. 行为级集成测试

* **现状：** 单测多集中在渲染、解析、config、policy 等确定性组件；`Agent.run()` 完整 ReAct 轨迹、工具失败降级、多 Agent 审查重试等行为级路径缺少可回放测试。
* **方向：** 录制 LLM 响应做可回放的行为级集成测试，保证重构编排器不破坏 Agent 决策质量。

### 5. 可靠性与真实世界打磨

* **动机：** 项目缺真实流量 / 并发 / 脏数据 / 断线的毒打，很多功能是“跑通 happy path”而非“扛得住”。
* **方向：** 对微信通道、MCP 传输、并行工具等关键路径做压测与故障注入（消息风暴、断线重连、并发会话、畸形输入），并沉淀至少一次真实“打崩-定位-修复”复盘。

### 6. MCP 高级能力补齐

* **现状：** MCP 做到 core + resources；sampling / OAuth / server 自动重启在 ROADMAP 未交付。
* **方向：** 补 `sampling/createMessage`、OAuth 鉴权、server 崩溃自动重启，并深化 prompt caching 用法，够到“MCP 高级能力”这一稀缺点。

## 第三部分 · 架构前沿对标（六大核心维度深度分析）

以下六个维度逐一做「实现现状 → 业界前沿 → 差距 → 最适合本项目方向」分析，是第一部分优先级排序的详细依据。

### 维度一、推理循环（ReAct / Plan-and-Execute）

**现状：** `Agent.run()` 是标准 ReAct while 循环——LLM 自主决定退出（不再调工具即返回），`AgentBudget` 仅在 token 耗尽/连续 3 轮相同工具调用(stagnation)/50 轮硬上限时兜底；工具结果回灌历史继续循环；多工具并行执行、结果按顺序回灌。`PlanExecuteAgent` 走先规划 DAG 再按拓扑批次执行，支持人审 review → 重规划。

**业界前沿：**

* Anthropic 把 agent 收敛为“LLM 在循环里自主用工具”，强调**智能足够时少加流程**，与本项目 ReAct 设计一致。
* **结构化反思 (Reflexion) 与错误自我恢复**成为提升长任务成功率的关键：失败观察显式回灌、让模型自评并调整策略。
* **just-in-time 探索优于预加载：** 用 glob/grep/read 边走边取，而不是一次性塞满上下文（本项目已是这种风格，方向正确）。

**纠错机制怎么实现的（实读代码确认）：** 本项目的纠错不是显式模块，而是**隐式回灌**——核心在 `ToolRegistry`：所有工具执行都被 `try/catch` 兜住，异常一律降级成**字符串结果**（如 `"读取文件失败: " + e.getMessage()`、`"🛡️ 策略拒绝: ..."`、`"工具执行失败: ..."`），并行批次超时降级为 `ToolExecutionResult.timedOut(...)`，取消降级为 `"用户取消了此次工具调用"`。这些字符串在 `Agent.run()` 里被当成正常 `tool_result` 塞回 `conversationHistory`，下一轮 LLM 看到错误文本后自行决定改参数重试 / 换工具 / 放弃。优点是简单且不打断循环，缺点是纠错质量完全依赖模型自己“悟”错误文本。兜底则靠 `AgentBudget` 的三道保险阀（token 预算默认无限、连续 3 轮完全相同工具签名判死循环、50 轮硬上限）。

**差距（基于代码实读）：**

1. **失败信号和成功结果长得一样，模型难分辨：** 工具失败只是一句以“失败/错误”开头的中文字符串，和正常返回混在同一个 `tool_result` 里，没有结构化标记（没有 `success:false` / `error_type` / `retriable` 字段）。模型要靠文本前缀猜这是不是错误、该不该重试——缺“失败 → 结构化反思 → 重试”的显式环节。
2. **stagnation 检测是“完全相同签名”精确匹配，易被绕过：** `AgentBudget.signatureOf` 用 `工具名 + 参数原文` 拼接判等，只要参数差一个字符（换个搜索词、改个路径、微调 JSON 空格）签名就不同，判不出“换汤不换药的无效循环”。对“反复 read 不同文件却毫无进展”这类真实死循环也识别不了。
3. **超时/异常被 catch 成普通文本，丢失了“这是超时/被拒”的语义：** 并行批次 `invokeAll` 超时后 `future.isCancelled()` 降级为 `timedOut` 字符串，但模型只看到一句话，无法据此判断“该缩小任务再试”还是“该放弃”。
4. **只有全局兜底，无单步预算/单步超时的软约束：** 全局有 50 轮和 batch 超时，但单个工具、单轮迭代没有软预算，long-horizon 任务里某一步空转不会被及时叫停。
5. **兜底命中后是硬失败返回，不给模型收尾机会：** 三道保险阀任一命中直接 `return "❌ ..."`，不给模型一次“基于目前进展做个总结/交付部分结果”的机会，长任务体验是“突然断掉”。

**最适合本项目的方向：**

* 🟢 **结构化工具结果 + 信号触发反思：** 让工具失败回传带明确标记（是否成功 / 错误类型 / 是否可重试），并在“工具报错、结果为空、验证类工具不通过、stagnation 命中”这些**明确信号**出现时才注入一段结构化反思（失败观察 + 根因假设 + 下一步建议），而非每步都反思。低成本、直接提升长任务成功率，能进 Eval 对比。（完整版见 §1.y，与维度四“失败记忆化”协同）
* 🟠 **细化 stagnation 检测：** 从“相同工具名+参数原文”升级到“相同工具 + 归一化后相似参数”的近似重复检测，再叠加“连续 N 轮无新增文件读取/无新信息进入上下文”的进展停滞信号，堵住精确匹配被绕过的无效循环。
* 🟡 **兜底命中改为“软收尾”：** 三道保险阀命中时，先给模型一轮“禁用工具、基于现有进展总结并交付部分结果”的机会，再返回，把“突然断掉”变成“体面收尾”。
* ⚪ **单步预算/超时：** 给单个工具或单轮迭代加软预算，long-horizon 任务下更可控（当前全局兜底已够用，优先级低）。

**能讲什么：** “这个项目的纠错是典型的‘异常降级成 tool_result 回灌让模型自己悟’——简单不打断循环，但我实读后发现三个盲区：失败和成功结果在协议层长得一样、死循环检测是参数原文精确匹配一改就绕过、兜底命中直接硬失败不给收尾。我把工具结果结构化(带 success/error_type/retriable)、在明确失败信号处做定向反思而非每步反思、并让死循环兜底改成软收尾。”——**能讲清 ReAct 的隐式纠错 vs Reflexion 显式反思的取舍、为什么不做 always-on 每步自评(token/延迟成本)、以及如何用 Eval 在‘开/关反思’两组上量化长任务成功率，是 agent 执行内核最硬核、最有区分度的深度故事。**

#### 1.x 重点演进方向：模式统一为单入口，plan / team 工具化（首选方向）

**问题：** 当前 ReAct / Plan / Team 是三个平级、由用户手动斜杠命令（`/plan` `/team`）切换的 Agent，默认走 ReAct。代码里没有任何复杂度分类 / 模式路由 / 自动选择逻辑（已实读确认，`SlashCommandHint` 明确是“下一条任务使用 X 模式”）。这在产品上是反直觉的——用户能选对模式的前提是理解 ReAct/Plan/Team 的内部区别，而绝大多数用户不知道、也不该知道这些概念。头部产品（Claude Code / Cursor）用户永远只是提问，agent 内部自主决定要不要规划、要不要派子 agent，用户感知不到“模式”。

**前沿：** 2026 趋势是把“选哪种推理策略”本身也交给模型（meta-reasoning / adaptive test-time compute）。而现代强模型在 ReAct 循环里已能“边做边规划”，“预先分类再硬路由”某种程度上是在用工程手段补正在被模型能力填平的短板。

**陷阱（为什么不做前置分类器硬路由）：** 如果加一个“先判断简单/复杂再路由”的前置分类器，等于引入一个新的、用户不可控的黑盒错误源——分类判错时用户无感知、无从纠正，体验比“自己选错还能重来”更差。把用户可控的选择换成不可控的赌博，是负优化。

**首选方向（本项目采纳）：弱化“模式”这个显式概念，让 ReAct 成为唯一入口，把 plan / team 变成 ReAct 内部的工具。**

* 只保留一个 ReAct 主循环作为用户入口；把规划能力封装成 `create_plan` 工具、把多 Agent 协作封装成 `spawn_subagents`（或类似）工具，注册进 ToolRegistry。
* 模型在循环中自主决定何时调用：遇到多步依赖任务就自己 `create_plan`，遇到可并行/需隔离上下文的子任务就自己 `spawn_subagents`，并把子 agent 结果摘要回灌主循环。
* 决策是动态、可在执行中修正的（对比前置分类器的一次性赌死），且用户始终面对一个入口——对齐 Claude Code“主循环 + 按需 spawn subagent”的做法。
* `/plan` `/team` 可作为高级用户的显式覆盖保留（透明、可打断、可覆盖），但不再是默认心智。

**落地要点：**

* 复用现有 `Planner` / `ExecutionPlan` 和 `AgentOrchestrator` / `SubAgent` 实现，把它们从“独立入口”改造为“被工具调用的能力”，而非重写。
* 子 agent 必须上下文隔离、只回传摘要（与维度三的 sub-agent 隔离、维度二的 worker 专精化天然协同）。
* 工具 schema 要写清“何时该用”，避免模型滥用规划/派生 agent（呼应 Anthropic“工具边界要清晰”的告诫）。

**前提：** 本演进必须在 Eval 闭环建好之后做。“单入口 + 工具化自主决策”相比“用户手选模式”到底好多少，是必须用数据回答的问题——需在同一批黄金任务上对比路由准确率、成功率、成本，否则改完无法判断是变好还是变差。

#### 1.y 反思模式 (Reflexion)：只做“信号触发的定向反思”，不做全局每步自评

**现状：** 项目没有任何显式反思 / 自我批判结构。ReAct 循环里工具报错时，是把原始错误直接回灌给模型自己悟：没有“失败→结构化反思→重试”的显式环节。上文的“工具失败反思回灌”是这个方向的窄版本，此处把它补充完整并划清边界。

**前沿：** Reflexion（自我反思 + 记忆化失败经验再重试）在学术上提升明显，但其收益高度依赖场景——主要出现在较弱模型 + 有明确成败信号的任务（如代码是否通过测试）上。现代强模型的 ReAct 循环本身已隐含反思（看到工具结果会自我调整）。

**陷阱（为什么不做 always-on 每步自评）：** 给每一步都加“回头审视自己做得对不对”，会显著增加 token 与延迟，而在强模型上往往是“花 2 倍成本换极小提升甚至负收益”。把隐含反思显式化并全局铺开，是典型的过度工程。

**最适合本项目的方向：**

* 🟢 **信号触发的定向反思：** 只在明确信号出现时才注入一段结构化反思提示（失败观察 + 根因假设 + 下一步建议），而非每步都做。触发信号：工具报错 / 结果为空、验证类工具（测试、编译、LSP 诊断）不通过、连续多轮无实质进展（复用现有 stagnation 检测）。
* 🟠 **失败经验短期记忆化：** 把同一任务内已反思过的失败模式记进短期记忆，避免反复踩同一个坑（对齐 Reflexion 的“记忆化”内核，但作用域限定在当前任务，不污染长期记忆）。
* ⚫ **全局每步自评：** 明确不做，仅在 Eval 数据证明特定弱模型 / 特定任务类型下有正收益时再局部开启。

**前提：** 反思是否真的提成功率、值不值那些 token，必须靠 Eval 在“开/关反思”两组上对比成功率与成本，否则容易自我感觉良好。

**落地设计（🟢 结构化工具结果 + 信号触发反思，可直接动手）**

**第一步：让失败在协议层可识别。** 当前 `ToolExecutionResult(id, name, argumentsJson, result, elapsedMillis, timedOut, imageParts)` 里，失败只体现在 `result` 字符串前缀（"读取文件失败：..."），`ToolOutput(text, imageParts)` 也没有成败位。改动分两层：

* `ToolOutput` 增加一个可选的 `ToolStatus`（`success` / `errorType` 枚举：`NOT_FOUND` / `INVALID_ARG` / `POLICY_DENIED` / `TIMEOUT` / `EXECUTION_ERROR` / `EMPTY_RESULT` / `OK`；`retriable` 布尔）。各工具的 `catch` 分支不再只拼字符串，而是同时给出 `errorType`（如 `readFileForTool` 的 `catch` → `NOT_FOUND` 或 `EXECUTION_ERROR`，`PolicyException` → `POLICY_DENIED` 不可重试，`timedOut` → `TIMEOUT` 可重试）。
* `ToolExecutionResult` 透传该状态。回灌给 LLM 的 `tool_result` 文本保持人类可读不变（不破坏现有 provider 兼容），状态只用于 `Agent.run()` 内部判断是否触发反思——即“对模型透明的元信息”，不塞进模型可见文本，避免污染上下文。

**第二步：在循环里检测失败信号。** `Agent.run()` 拿到 `List<ToolExecutionResult>` 后（`executeToolCalls` 返回处），按信号判定是否注入反思：

* **信号 A：** 本次存在 `success=false` 且 `retriable=true` 的结果（排除 `POLICY_DENIED` 这类反思也没用的硬拒绝）。
* **信号 B：** 结果为空 / `EMPTY_RESULT`（如 grep 无命中、read 到空文件）。
* **信号 C：** 验证类工具不通过——复用已有 `flushPendingLspDiagnostics` 的 LSP 诊断、以及 `execute_command` 跑测试/编译的非零退出。
* **信号 D：** `AgentBudget` 的 `stagnation` 已置位（连续无进展），此时反思优先于直接兜底退出。

**第三步：注入一段结构化反思提示（而非每步都注）。** 命中信号时，向 `conversationHistory` 追加一条 `user`（或 system-turn）消息，模板固定：

> **[反思] 上一步出现问题信号：**
> - 观察：`<失败工具名 + errorType + 结果摘要>`
> - 请先判断根因（参数错误 / 前提不成立 / 该换工具 / 信息确实不存在），再决定下一步；如果连续尝试同类操作已无进展，考虑换思路或如实告知无法完成。
> 不要重复上一步完全相同的调用。

**关键约束：** 同一任务内每种失败信号最多注入一次反思（用一个 `Set<errorType+toolName>` 去重），避免反思本身变成 token 黑洞；这也天然衔接 🟠“失败经验短期记忆化”——去重集合就是最轻量的“本任务失败记忆”。

**第四步：Eval 对比。** 在 golden task 集上跑“关反思 / 开反思”两组，指标：长任务成功率、平均迭代轮数、平均 token、撞兜底（`stagnation`/硬轮数）比例。只有开反思组成功率显著提升且 token 增幅可接受时才默认开启，否则保留为可配置开关（呼应“不做全局每步自评”的克制原则）。

**改动边界：** 全部集中在 `ToolOutput` / `ToolExecutionResult` / `ToolRegistry` 各 `catch` 分支 / `Agent.run()` 信号检测这几处，不动 provider、不改 `tool_result` 回灌的 wire 格式，风险可控、可灰度、可 Eval 量化。

#### 1.z 主动反问用户 (Clarification / Elicitation)：填补真空白，性价比最高

**现状：** 项目完全没有 agent 主动反问用户的能力。它有 HITL（用户对危险操作做审批）和 Plan review（用户审阅计划），但这两个方向都是“**用户被动裁决 agent 的提议**”；缺的是相反方向——agent 发现信息不足时主动停下来问用户。信息缺失时它现在只能猜一个继续，猜错就是整轮白干甚至改错文件。

**前沿：** clarification / elicitation 是 2026 agent 产品化的核心可靠性能力，MCP 协议专门定义了 elicitation（向用户索要结构化输入）。能反问的 agent 与只会瞎猜的 agent，可靠性差一个数量级。

**为什么这个项目做它成本极低：** 已有 HITL 的完整交互基础设施（`HitlHandler` 终端 / 渲染器 / 可切换三实现、审批弹框、CJK 宽度渲染）。做“反问”不是从零造，而是把这套“停下来等用户输入”的机制从“审批 y/n”扩展成“回答一个问题”。

**最适合本项目的方向：**

* 🟢 **新增 `ask_user` 工具：** schema 为 `question` + 可选 `options`（结构化选项）。模型判断信息缺失时调用 → 复用 `HitlHandler` 弹出提问 → 用户输入回灌成 `tool_result` → ReAct 循环继续。完全长在现有 ReAct + 工具 + HITL 架构上。
* 🟢 **前端结构化提问：** 当前运行环境（CatDesk 前端）可把 `ask_user` 渲染成弹框 / 结构化选项，体验直接对齐头部产品。
* 🟡 **非交互通道降级：** 微信 iLink 等无人工面板通道，`ask_user` 需有默认降级策略（超时 / 默认拒绝或走预设值），与现有 `WechatPolicyDecider` 非交互默认拒绝一致。

**唯一要守的边界：** 工具 schema 必须明确“仅在信息缺失会导致做错、且无法通过其它工具自行查明时才问”，防止 agent 滥用反问变成话痨、把该自己探索的事推给用户（呼应 Anthropic“工具边界要清晰”）。

**落地设计（🟢 `ask_user` 工具，可直接动手）**

**技术选型：复用交互底层，不复用审批语义（关键决策）。** 实读 HITL 后确认，现有 `HitlHandler.requestApproval(ApprovalRequest)` → `ApprovalResult` 是为审批语义硬绑定的——返回类型 `ApprovalResult.Decision` 是 `APPROVED` / `REJECTED` / `MODIFIED` / `SKIPPED` … 这套审批专用枚举，而“反问”的返回是一段自由文本答案，不是“批准/拒绝”。因此选型不是“把 `ask_user` 塞进 `requestApproval`”，那会语义错位、污染审批模型；而是在 `HitlHandler` 接口上并列新增一个交互原语 `String askUser(ClarificationRequest)`，各实现（Terminal / Renderer / Switchable）分别实现。要复用的是底层能力，不是审批数据结构：

| 复用（已验证存在） | 为什么 |
| :--- | :--- |
| `TerminalHitlHandler` 的 `synchronized` 方法级锁 + 共享 `in/out` | 多 Agent 并行时序列化 stdin/stdout，避免提问和审批框互相串扰——这是并发正确性的关键，从零写容易踩坑 |
| `ApprovalRequest.toDisplayText()` 里的 CJK/emoji 显示宽度渲染（`displayWidth` / `wrapByDisplayWidth` / 盒子边框） | 提问框直接对齐现有审批框视觉、中文不挤歪边框，零额外成本 |
| `BufferedReader.readLine()` 的同步阻塞读 + 输入流关闭 / `IOException` 的 fail-safe 兜底 | 提问也需要“流关闭时给个安全默认值”，逻辑同构 |
| `SwitchableHitlHandler` 三实现可切换的分发结构 | Terminal / 前端 Renderer / 微信非交互三通道天然各自实现 `askUser` |
| **不复用** `ApprovalRequest`/`ApprovalResult` 数据结构 | 审批是“裁决 y/n”，反问是“回答文本”，语义不同，强行共用会把两个模型耦死 |

**数据结构：** 新增 `ClarificationRequest(question, List<String> options, String defaultAnswer)`；返回直接用 `String`（自由文本或选中的 option）。`options` 为空即自由问答，非空则渲染为编号选项让用户选序号或直接输入。

**第一步 · 注册 `ask_user` 工具。** 在 `ToolRegistry` 注册，schema: `question`（必填）+ `options`（可选 string 数组）。工具体调用 `hitlHandler.askUser(...)`，把用户答案作为 `ToolOutput.text(answer)` 返回——天然走现有 `tool_result` 回灌链路，ReAct 循环无需任何改动就能继续（这正是把它做成“工具”而非“特殊循环分支”的选型理由：复用度最高、对循环零侵入）。

**第二步 · 三通道分别实现 `askUser`：**

* **`TerminalHitlHandler`：** `synchronized` 内打印问题框（复用 `wrapByDisplayWidth` 渲染），`readline()` 读答案；有 options 时校验序号，非法重问（复用 `promptUntilDecision` 的 5 次重试 + fail-safe 模式）。
* **`RendererHitlHandler`：** 把 `ClarificationRequest` 交给前端渲染成弹框 / 结构化选项（CatDesk 前端），体验对齐头部产品。
* **微信 iLink 通道：** 非交互降级——与现有 `WechatPolicyDecider` 默认拒绝原则一致，`askUser` 走 `defaultAnswer`（若提供）或直接返回“当前通道无法向用户提问，请基于已有信息继续或如实说明无法完成”，让模型自行收敛，绝不阻塞挂死。

**第三步 · schema 描述里写死使用边界：** 工具 `description` 明确“仅当信息缺失会导致做错、且无法用其它工具（read/grep/glob/web）自行查明时才调用；能自己查的绝不问”。这是防滥用的第一道闸（呼应 §1.y“工具边界要清晰”）。

**第四步 · Eval 验证：** 造一批“需求本身有歧义 / 缺失关键参数”的 golden task，对比“无 `ask_user`（只能猜）” vs “有 `ask_user`”。指标：任务做对率、改错文件率（猜错的代价）、以及 `ask_user` 的**误触发率**（信息其实够却乱问，反向指标，防话痨）。

**改动边界：** `HitlHandler` 接口 +1 方法、三实现各 +1 实现、`ToolRegistry` +1 工具注册、新增一个 `ClarificationRequest` record；**不动 ReAct 主循环、不动 provider、不动审批链路**。风险面小、复用度高，是执行内核里“从只会瞎猜到会反问”这一质变的最低成本落点。

### 维度二、多 Agent 协作

**现状：** `AgentOrchestrator` 主从架构，硬编码 1 planner + 2 worker（池化轮转）+ 1 reviewer；orchestrator 直接方法调用 SubAgent，`AgentMessage` 的 6 种类型多数未实际使用；DAG 由 LLM 输出 JSON 解析而来；同批次并行（≤2），reviewer 审查不过带 issues 重试（≤2 次）；SubAgent 每步 `clearHistory()`，步骤间不积累上下文；reviewer LLM 失败时放行。存在两套不兼容的 plan 格式（Orchestrator 的 steps vs Planner 的 tasks）。

**业界前沿：** 三大架构已成共识——

* **Supervisor（主管）：** 中央协调，本项目属于此类。
* **Swarm（蜂群）：** agent 间按专长动态 handoff、系统记住“最后活跃 agent”。
* **Hierarchical（分层）：** 多层 supervisor。
* **关键趋势：** handoff 作为一等原语（LangGraph Command）、worker 按专长分化（代码/测试/文档专精）、子 agent 上下文隔离 + 结果摘要回传主 agent（Anthropic 明确推荐用 multi-agent 做 context 隔离）。

**差距：** worker 同质化（都是同一提示词）；无 handoff；`AgentMessage` 名义存在但通信实际靠返回值 + JSON 解析；reviewer 失败即放行有质量失控风险；两套 plan 格式增加维护负担。

**最适合本项目的方向：**

* 🟢 **worker 按任务类型分化专精提示词：** 给 Task 的 5 种类型（FILE_READ/WRITE/COMMAND/ANALYSIS/VERIFICATION）配差异化角色提示词，最贴合已有结构、改动小、效果直观。
* 🟢 **reviewer 失败改为 fail-safe 重试而非放行：** 与 `CommandGuard` 的 fail-safe 原则对齐，避免网络抖动导致审查被跳过。
* 🟡 **统一两套 plan 格式：** 把 Orchestrator 的 steps 归一到 Planner 的 tasks，消除重复维护。
* ⚪ **handoff 机制：** 本项目 CLI 场景对 swarm 式动态转交需求不强，属于了解性方向。

### 维度三、上下文管理

**现状：** `ContextProfile` 是唯一策略中枢，参数由 `maxContextWindow` 推导；主轨可用上限 = `window − 20k − 8k`，另需消息体 ≥ 20k；`ConversationHistoryCompactor` 执行 Context Checkpoint Compaction（Pre-Turn / Mid-Turn / API 兜底），摘要失败走渐进裁剪再硬截断；总量取 `max(API usage, 估算+schema)`。prompt 分层组装；token 仍以字符估算为主，API usage 优先。检查点写入 `session-*.jsonl`，`/clear` 经 `StickySessionRotator` 轮换。

**已落地：** 硬截断兜底、Session Notebook（`notebook_write/read`）、session.jsonl 检查点 Resume、压缩摘要进 `session_search`、Tools Schema 计入溢出判定。

**业界前沿（Anthropic·官方）：** 核心心智是 **context 是有限资源、存在 context rot（越长召回越差）与 attention budget**。四大策略：

* **Compaction（压缩）：** 接近窗口时摘要历史——本项目主轨已对齐 1024 Checkpoint。
* **Structured note-taking（结构化笔记/记事本）：** 把关键信息 offload 到 context 外（文件/notes），需要时再读取，避免有损摘要丢信息——本项目已有 Session Notebook。
* **Just-in-time 检索：** 维护轻量标识符（路径/查询），运行时按需加载，而非预加载。
* **Sub-agent 隔离：** 用子 agent 承接高 token 子任务，只回传摘要。

**差距（基于代码实读，按严重程度排序）：**

1. ~~压缩失败撞窗口~~（已硬截断兜底）。
2. ~~缺 structured note-taking~~（已有 `notebook_write/read`）。
3. **压缩仍偏时间线切分，对「任务目标 / 关键约束」缺少显式分级保留**（记事本可补；主轨检查点已钉住最早真实用户消息 + 摘要强制分节）。
4. **token 仍以字符估算为主，** API usage 已接入但仍有 Mid-Turn 后估算误差。
5. **prompt 九层全量拼接无总长度护栏：** `PromptAssembler` 的 project context + skills 注入无硬预算（仅记忆检索侧有 token 上限）。

**最适合本项目的方向：**

* ✅ 压缩失败硬截断兜底（已做）。
* ✅ Session Notebook structured note-taking（已做）。
* 🟡 **分级保留：** 压缩时钉住最早真实用户消息（任务种子）+ 近期用户原文；摘要提示强制「任务目标 / 关键约束 / 进展 / 未完成 / 关键数据」分节。记事本仍可补结构化笔记。
* 🟡 **prompt 组装加总长度护栏：** project context + skills 注入硬预算，超限按优先级裁剪。
* ⚪ **接入真实 tokenizer(BPE)替代字符估算：** 更准但工程量大、收益边际，优先级低。

**能讲什么：** “我发现项目唯一的上下文保命路径——压缩——在摘要 LLM 失败时会直接跳过导致撞窗口崩溃；我加了硬截断兜底把它从‘会崩’变成‘必不崩’；再引入记事本把有损摘要升级为‘摘要 + 可读回的结构化笔记’，让长任务里关键决策不再丢失。”——能讲清 context rot、attention budget、有损压缩的信息损失代价，并用 Eval 量化‘长任务成功率 / 撞窗口率’的改善，是很有区分度的上下文工程深度故事。

### 维度四、记忆系统

**现状：** 短期记忆是内存 `LinkedHashMap` + token 滑动窗口淘汰（超预算逐条 `evictOldest`）；长期记忆是 `ConcurrentHashMap` + 全量 JSON 文件持久化（每次写全量 rewrite）；检索是**纯关键词子串匹配**（jieba 分词 → contains → matchedWords/queryWords × 时间衰减，长期记忆 ×1.2），**无向量/BM25/TF-IDF**；写入以 `/save` 显式为主，`ContextCompressor.extractFacts` 可 LLM 自动提取（带 EPHEMERAL/DURABLE 关键词过滤）；作用域用 metadata 的 `scope=global|project` + realpath 归一化实现。

**业界前沿：**

* **Mem0 范式：** 动态抽取 → 整合（去重/冲突消解）→ 检索，语义检索为主，相比原生记忆 token 降 90%、延迟降 91%。
* **记忆分类学（2025 综述）：** 按形式/功能/动态三维划分，强调 **episodic（情节）/ semantic（语义）/ procedural（程序）** 记忆分层，以及“自我遗忘 + 冲突修正”能力。
* **语义检索 + 图记忆：** Letta/Zep/Cognee 等用向量+图，超越关键词。评测已从单一 retrieval accuracy 转向 **coherence（一致性）** 与 **evolution efficiency（演化效率）**。

**差距（基于代码实读，按严重程度排序）：**

1. **检索是纯关键词子串匹配（最大短板）：** `MemoryRetriever.computeRelevanceScore` 是 jieba 分词 → contains 子串命中 → `matchedWords/queryWords` 比例，同义/改写/语义相近的查询全查不到。`buildContextForQuery` 注入 system 的相关记忆完全依赖它，召回不准则记忆形同虚设。
2. **时间衰减对长期事实是负担而非帮助：** `computeRelevanceScore` 里 `timeDecay = max(0.5, 1 - ageHours/24)`，24 小时后所有记忆都衰减到下限 0.5。但长期记忆存的是“用户偏好/技术栈”这类**越老越稳定**的事实，用对话式时间衰减压它的分数，方向反了。
3. **自动写入几乎从不发生：** `ContextCompressor.extractFacts` 虽然能 LLM 提取事实，但通读 `MemoryManager` 没有任何路径调用它——长期记忆实际上只靠用户 `/save` 显式写入。“self-improving/自动积累”只是设施存在、链路未接。
4. **去重/整合能力弱：** `LongTermMemory.store` 去重只认 `content` **完全相等**，改写一个字就存两条；无近似合并、无冲突消解（新事实与旧事实矛盾时是叠加而非更新）。
5. **持久化是每次全量 rewrite 且无并发保护：** `store/delete/clear` 每次都把整个 `entries` 序列化重写整个 JSON 文件，条目一多 **IO 放大明显**；`ConcurrentHashMap` 只保证单次 put 原子，`saveToDisk` 读集合期间并发写入会有一致性风险，也无文件锁。
6. **短期记忆淘汰是 FIFO 逐条 evict，不看价值：** `ConversationMemory` 超预算就淘汰最旧一条并塞进 `compressedSummaries`，但这些被淘汰内容默认不会被摘要回注（要外部显式调 `compress + injectSummary`），等于**静默丢弃**。
7. **token 估算字符级、`ExplicitMemoryHints` 硬编码：** 估算 `中文/1.5 + 其它/4` 误差大；hints 是针对“浏览器登录态”的**硬编码**规则，通用性弱。

**最适合本项目的方向：**

* 🟢 **长期记忆检索升级为语义检索：** 复用 RAG 现代化后的 embedding/向量能力（见维度六），让记忆检索从子串匹配升级为向量召回 + 关键词兜底。与 RAG 改造共享底座，一次投入两处收益，直接堵住最大短板。
* 🟢 **打通自动记忆写入链路：** 把已有的 `extractFacts` 真正接进任务结束/压缩时机（带 EPHEMERAL/DURABLE 过滤 + 用户可审计撤销），让长期记忆从“只靠 /save”变成“自动积累 + 显式补充”；这才对得起“记忆系统”四个字，且和维度一 §1.y 失败记忆化、维度三记事本天然协同。
* 🟠 **检索打分按记忆类型区分：** FACT 类不吃对话时间衰减（甚至越稳越加权），CONVERSATION/TOOL_RESULT 类才衰减；修掉“长期事实被时间衰减压分”这个方向性错误，改动集中在 `computeRelevanceScore`。
* 🟠 **记忆整合（去重/冲突消解）：** 写入时对近似重复做合并、对冲突事实做更新而非叠加，对齐 Mem0 的 consolidation。
* ⚪ **图记忆/情节-语义分层：** 本项目定位是编码 CLI，记忆规模有限，分层记忆属于了解性方向。

**能讲什么：** “我发现项目名义上有记忆系统，但实读代码后暴露三个真问题——检索是纯子串匹配（同义查不到）、时间衰减把越老越稳的长期事实反而压低分、以及自动提取事实的链路根本没接上导致长期记忆只靠手动 /save。我把检索升级为语义召回、按记忆类型区分打分、并打通自动写入，让记忆从‘摆设’变成真正会随使用积累的资产。”——**能讲清 Mem0 的抽取 → 整合 → 检索范式、episodic/semantic/procedural 分层、以及“记忆演化效率”这类前沿评测视角，是既有前沿认知又有代码实证的深度故事。**

### 维度五、安全审计与策略层

**现状：** 三层拦截 `HitlToolRegistry`（人审）→ `PathGuard`（路径围栏，`toRealPath` 解析符号链接、防 `..` 穿越、对新建文件向上找存在祖先校验）→ `CommandGuard`（9 条正则黑名单，fast-fail）；审计 JSONL 按天分文件，记录 tool/args/outcome/approver/durationMs，args 经 sanitize 脱敏（仅覆盖 token/key/password/secret/authorization 5 个关键词）+截断；审批 6 种决策（单次/全放/server 级全放/拒绝/改参/跳过）；微信通道非交互式默认拒绝 + 白名单全等匹配 + 日配额。明确声明不是沙箱、无进程隔离。

**业界前沿：**

* **microVM/gVisor/Firecracker 成主流：** OpenAI Code Interpreter 基于 gVisor，Anthropic Cowork 用 Apple Virtualization.framework，E2B 沙箱月创建量暴涨——共识是 **agent 执行的代码本质不可信，共享内核的容器隔离已不够。**
* **default-deny egress + 无凭证 + 硬资源上限 + 全审计：** 威胁不是模型本身，而是被注入的输入导致信任边界内 RCE。
* **prompt injection / tool poisoning** 成为 MCP 时代新攻击面，需工具级最小权限。

**差距：** 无任何进程/文件系统/网络隔离，`execute_command` 直接 `ProcessBuilder` 在宿主机跑；脱敏关键词覆盖不全（漏 `api_key`/`access_token`/`credential` 等）；CommandGuard 无 shell 解析，`echo ... | base64 -d | sh` 可绕过；审计无防篡改（无哈希链）、无查询/告警；MCP 工具无只读/写入细粒度权限区分。

**最适合本项目的方向：**

* 🟢 **补齐脱敏覆盖面 + 环境变量值脱敏：** 低成本堵住审计日志泄密，直接可做。
* 🟢 **MCP 工具细粒度权限：** 按工具语义区分只读/写入，只读 MCP 工具免审批，减少无谓弹窗同时收紧写操作——契合“最小权限”前沿且改善体验。
* 🟡 **可选进程隔离层（渐进式）：** 在 macOS/Linux 上给 `execute_command` 加可选的轻量隔离（如 `sandbox-exec` / `bwrap` / 资源 ulimit），不必上 microVM，但补上“有隔离选项”这一前沿姿态。这是与生产标准差距最大、也最能讲的一块。
* ⚫ **审计哈希链防篡改 / 审计查询告警：** 企业级才需要，个人项目属于了解性方向。

### 维度六、代码 RAG 理解

**现状：** Java 走 JavaParser AST 分块（class 头 5 行 + method 级），非 Java 按行切（≤2000 字符）；embedding 默认 Ollama nomic-embed-text；向量以 **JSON 字符串 SQLite**，检索是**暴力全表扫描逐个算余弦**，无 ANN 索引；`CodeRetriever.hybridSearch` 有向量+关键词 LIKE 的**启发式加权融合**（魔数权重），但 **无 RRF、无 cross-encoder rerank**；`CodeAnalyzer` 抽 extends/implements/calls 等关系存表，但 calls 只按方法名字符串匹配（无类型解析），图谱与语义检索割裂；索引是先 `clearProject` 再全量重建，**无增量**。项目定位是“RAG 语义辅助”，主力代码理解走实时 grep/glob/read。

**业界前沿：**

* **AST-aware chunking + 代码专用 embedding + 元数据过滤的向量索引 + rerank + git push 增量索引** 成为标准 code RAG 流水线。
* **CodeRAG（EMNLP 2025）：** 多路径检索 + BestFit rerank，显著超 SOTA。
* **混合检索用 RRF（倒数排名融合）** 而非手调权重；**cross-encoder rerank 是提召回精度的关键一环。**
* **graph-augmented retrieval：** 把调用图/依赖图与向量检索融合。
* **Anthropic 观点补充：** Claude Code 刻意用 **grep/glob 而非重索引**，避免 stale index 与语法树复杂度——即 **“agentic search 优于重 RAG”** 在编码场景成立。

**差距：** 暴力扫描 O(N×dim)，数千块以上退化；融合靠魔数无 RRF、无 rerank；embedding 2000 字符硬截断丢大方法尾部；calls 无符号解析、图谱与向量割裂；无增量索引。

**最适合本项目的方向（与第二部分 §2 RAG 现代化呼应）：**

* 🟢 **混合检索融合改用 RRF + 加 rerank：** 把手调魔数权重换成 RRF，再对 topK 做一次 rerank（可用轻量 cross-encoder 或让 LLM 打分）。改动集中在 `CodeRetriever`，不动分块/存储，性价比最高且直接对标 CodeRAG。
* 🟢 **向量检索换 ANN 索引：** SQLite 向量暴力扫描换成 HNSW（如 sqlite-vec / 本地 HNSW 库），解决规模退化，且能与维度四的记忆语义检索共享底座。
* 🟡 **增量索引：** 按文件 mtime/git diff 只重建变更文件，去掉“先 clear 再全量”。
* 🟡 **graph-augmented retrieval：** 检索命中符号时，把其 calls/依赖关系一并作为上下文补充（需先给 calls 加类型解析）。
* ⚪ **代码专用 embedding 模型替换 nomic：** 收益取决于可获得的模型，属可选调优。

一个重要的战略判断：Anthropic 明确指出编码场景 **agentic search（grep/glob 实时探索）常优于重 RAG**——本项目主力路径已是 grep/glob/read，这是正确选择。所以 RAG 现代化的目标**不是让 RAG 变主力**，而是让它在“模糊语义检索/跨文件知识关联”这个辅助定位上真正好用，不必追求把整个代码库做成重索引系统。

## 第四部分 · 把项目做出深度（可执行的纵深方向）

第一～三部分是“把已知短板补齐、追上 2026 标准动作”，做完项目会合格但不惊艳——每一个维度都及格，没有一个点能撑起面试官的连环追问。本部分回答一个不同的问题：**怎么在某个点上做到“别人做不到 / 做不好”的深度。**

**筛选标准（关键）：** 这里只保留真正触及 agent 认知内核的方向——即 agent 怎么推理、规划、用工具、积累经验、自我改进。凡是本质属于“工程化/产品化/领域堆料”（如领域场景下注、日志结构化与回放、隔离标注与黑白名单等）的方向，无论工程量多大，**都不算 agent 技术深度**，一律不列入本部分（可作为工程完善项另行处理）。

**判定“够不够深”的唯一标准：** 这个方向能不能撑住四连问——“为什么这么做 → 那种方案为什么不行 → 这样有什么代价 → 你怎么用数据证明它有效”。撑得住才叫深，撑不住只是“又加了个功能”。

按此标准，只保留两个方向——它们恰是唯一触及 agent 认知内核、且长在现有代码骨架上、可用数据佐证的纵深。二者互为证据（4.2 的“学习曲线”正是用 4.1 的 Eval 度量出来的），组合即足够，无需再铺开。

### 4.1 【首选】Eval 驱动的自我改进闭环——把“能跑”变成“可证明、可迭代”

* **为什么深：** agent 最核心的追问是“你怎么知道你的 agent 是好的”。绝大多数人答不上来。把 Eval 从“离线人工触发”做成“度量 → 定位 → 改进 → 再验证”的闭环，是把工程师思维闭环显式化。
* **怎么做（在第二部分 §3 Eval 基础上纵深）：**
  * **可归因的失败分析：** Runner 跑完不只给成功率，还把失败样本自动归类——是 prompt 问题、工具 schema 描述不清、还是模型能力问题（可用 LLM-as-judge 对失败 trace 做根因标注）。
  * **A/B 对比矩阵：** 同一批黄金任务上跑“基线 vs 加反思 vs 加反问 vs 换模型”，输出成功率/延迟/成本的多维对比表，让每个改动都有“提升 N%、代价 M% token”的量化结论。
  * **prompt/工具描述的半自动优化（进阶，对标 DSPy 思路）：** 以 Eval 分数为信号，对 prompt 或工具 schema 做候选变体的自动打分筛选，而非人肉调。
* **能讲什么：** “我建了 N 条黄金任务集，测出 ReAct vs Plan 成功率 X%/Y%，加失败反思后从 X% 提到 Z%、延迟涨 N%；某类任务失败集中在工具描述歧义，我改了 schema 后召回对了。”——这一套“假设 → 度量 → 改进 → 验证”的故事，直接把你和只会说“我测了几个 case 感觉还行”的人拉开代际差。
* **如何证明：** 对比报告本身就是证据；commit 里能看到“改动 + 对应 Eval 数字变化”。

### 4.2 【护城河】项目级程序性记忆 (Procedural Memory)——让 agent 越用越懂这个 codebase

* **为什么深：** 现有记忆升级（维度四）只做到“语义检索事实”，仍是被动存取。真正稀缺的是 **procedural memory**——把 agent 在这个具体项目里走通的“打法”沉淀成可复用路径（如“改鉴权 = 动这三个文件 + 跑这个测试验证”），下次遇到同类任务直接复用而非从头探索。这是 self-improving agent 的内核，也是最难被复制的粘性。
* **怎么做：**
  * 任务成功结束时，抽取“任务类型 → 关键文件 → 操作序列 → 验证方式”存为结构化“经验条目”（可复用长期记忆 + RAG 底座）。
  * 新任务开始时，检索相似历史经验作为 **few-shot** 提示注入，加速探索、减少试错轮数。
  * 与维度一 §1.y 的“失败经验记忆化”天然协同：成功路径与失败教训一起沉淀。
* **能讲什么：** “它不是通用 Claude Code，而是在这个 codebase 上越用越懂——第二次做同类任务的迭代轮数/token 明显下降。”这是能讲“护城河/迁移成本”的方向。
* **如何证明：** Eval 里设计“同类任务第一次 vs 第 N 次”的对比，看迭代轮数、成功率、token 是否随经验积累改善。这个“学习曲线”图表是极强的面试素材。

### 4.3 【被低估的胜负手】工具层做深——工具设计质量直接决定任务成功率

* **为什么深：** 模型是大脑、工具是手脚；同样的模型，工具设计得好不好，成功率能差一倍。工具不是“把函数暴露给模型”那么简单，它触及 agent 认知内核的两件事——模型能不能**选对**工具、能不能**用对**参数、能不能从工具失败里**恢复**。Anthropic 明确把“为 agent 设计工具”当作一门独立手艺。这是本项目当前广度够、深度不均衡的一块，做深性价比高且极能讲。
* **现状诊断（基于代码实读）：**
  * **schema 质量参差不齐：** `grep_code` 是好范本（9 个参数、每个标默认值/上限、描述里引导“找到后再 read_file”）；但 `execute_command` 只有单个 `command:string`、`list_dir` / `write_file` 也很朴素——同一项目内工具设计水平不统一。
  * **错误返回是“人话字符串”而非“可指导下一步的结构化信息”：** 如 `"读取文件失败: " + e.getMessage()`、`"目录为空或不存在"`。模型拿到只能猜——是路径错、权限、还是该换工具，全靠猜。
  * **缺“工具选择引导”层：** 16 个内置工具 + N 个 MCP 工具全平铺给模型自己挑；工具越多，选错/滥用概率越高。
  * **结果预算不统一：** `grep_code` / `read_file` 有字符/行预算，`list_dir` / `execute_command` 的输出缺同等的“上下文友好”约束。
* **怎么做（按性价比排序）：**
  * 🟢 **错误返回结构化 + 可操作：** 工具失败时返回“错误类型 + 原因 + 建议下一步”（如“路径不存在，建议先 glob_files 定位”“命中被 max_chars 截断，建议缩小 pattern 或调大预算”），把错误从“告知”升级为“引导恢复”。与维度一 §1.y 的失败反思天然协同。
  * 🟢 **拉齐 schema 质量基线：** 给 `execute_command` 等朴素工具补充参数约束、用途边界、何时该用/不该用 的描述，让模型少填错、少误用。
  * 🟡 **统一结果预算与截断策略：** 所有工具输出走统一的“上下文预算 + 语义化截断（保头尾、标注被截）”，避免单个工具结果挤爆上下文。
  * 🟡 **工具分组 / 渐进式暴露：** 工具多到一定程度时，按场景分组或按需暴露子集（progressive disclosure），降低模型的选择负担与误选率。
* **能讲什么：** “我发现工具的错误返回方式直接影响 agent 的自恢复能力——把 read_file 失败从‘读取失败: xxx’改成带‘建议先 glob 定位’的结构化提示后，同类任务的无效重试轮数下降、成功率提升 N%。”——**这是能体现“我懂 agent 工具设计手艺、且用数据验证”的深度故事，而且大多数候选人根本想不到这个层面。**
* **如何证明：** 在 4.1 的 Eval 上做 A/B——“朴素错误返回 vs 结构化可操作错误返回”，对比同批任务的成功率、平均迭代轮数、无效重试次数。

### 深度方向优先建议

三个方向按下注顺序：

1. 🟢 **4.1 Eval 自我改进闭环**——一切深度的度量前提，且本身就是最强面试故事，必做。
2. 🟢 **4.3 工具层做深**——实操门槛最低、见效最快的切入点，可作为 4.1 建好后的第一个被度量的改动；错误返回结构化直接提成功率。
3. 🟢 **4.2 项目级程序性记忆**——唯一能讲“护城河/越用越懂”的方向，与 4.1 的学习曲线互为证据。

> **被排除的方向（明确记录，避免反复纠结）：** 场景下注（Java 深耕 / 企业内网自托管）、对抗性鲁棒性（injection 防御的工程手段部分）、可观测性（决策回放）——这些价值不低，但本质是工程化 / 产品化 / 领域堆料，**不触及 agent 认知内核**，因此不作为“做出 agent 技术深度”的方向；若要做，归入工程完善项，不占本部分心智。

**统一约束：** 以上方向落地后，都必须回到 4.1 的 Eval 闭环量化“改动前后的成功率/延迟/成本”等指标——**深度不是“我做了个复杂的东西”，而是“我用数据证明了这个复杂的东西带来了可度量的价值，并说得清它的代价与边界”。**
