# 05 显式状态机与全链事件追踪

## 一、背景：while(true) 循环的可观测性困境

Agent 框架跑起来之后，最难回答的一个问题是"刚才到底发生了什么"。

PAICLI 的 ReAct 循环用 `while(true)` 驱动，每一轮的推进靠日志记录。日志能告诉你"这一轮调了哪些工具、LLM 返回了什么"，但当用户问"为什么这个任务在第 7 轮突然失败了"、"为什么模型在这一轮决定不再调用工具"、"这次执行和上次执行有什么不同"时，日志很难给出结构化的答案——它是一串文本流，没有状态边界，没有因果链路，要靠人脑从大量日志里拼凑出执行轨迹。

这个困境在调试和复盘时尤其明显。PAICLI 有 `AuditLog` 记录工具调用，有 `LlmTraceLogger` 记录 LLM 请求响应，但这些记录是分散的、按事件类型的，不是按"执行状态"组织的。当一次 ReAct 执行出问题，用户想回放整个执行过程看哪一步走偏了，没有现成的机制——得把日志、audit、trace 几个来源手工对齐时间线，费时且容易漏。

更深层的问题是，PAICLI 的循环状态是隐式的。循环处在什么阶段（思考中、工具执行中、收尾中）、为什么进入这个阶段、为什么离开这个阶段，这些都藏在 `while(true)` 里的 if-else 分支和控制变量里，没有显式的状态对象。这意味着状态转换的合法性无法被校验——理论上不该出现的"从收尾直接跳回工具执行"在代码里没有防护，全靠流程自然不走到那里。一旦某次重构改乱了流程，问题会在运行时才暴露，而不是在编译时或状态机定义时就被发现。

MyCodeAgent 在这件事上做了一套显式状态机加全链事件追踪的设计，把 Agent 执行变成了可观测、可回放、可校验的结构化过程，值得 PAICLI 借鉴。

## 二、PAICLI 现状：隐式状态，分散记录

看清楚 PAICLI 现在的循环和记录方式。

`Agent.java` 的主循环是一个 `while(true)`，循环体里按顺序做：组装 prompt → 调 LLM → 判断有无 toolCalls → 有则执行工具并回灌 → 无则收尾跳出。循环的"状态"实际上是程序计数器——执行到哪一行就是哪个状态，没有显式的状态变量。这意味着你没法在循环外问"现在循环处于什么状态"，只能通过日志推断。

记录方面，PAICLI 有几个独立的记录器。`AuditLog` 记录工具调用和策略决策（PathGuard 拒绝、HITL 审批等），按工具调用事件组织。`LlmTraceLogger` 记录 LLM 的请求和响应，按 LLM 调用事件组织。`SessionMessageStore` 记录会话消息，按消息组织。这三个记录器各自独立，时间线对齐靠时间戳，没有统一的执行上下文把它们串起来。

`SnapshotService` 按 ReAct 轮次做 git 快照，轮次是它的组织单位，但它不记录轮次内的状态变化。`/export` 能导出 conversationHistory 为 Markdown，但那是消息序列，不是执行状态轨迹。

这套现状的问题在于，可观测性是"事后拼凑"式的，不是"原生结构化"式的。要理解一次执行，得从多个来源拼；要回放一次执行，没有机制；要校验执行流程的合法性，没有状态机定义可对照。PAICLI 的三条路径（ReAct、Plan-and-Execute、Multi-Agent）各有各的循环结构，状态隐式程度更高，跨路径的执行对比几乎不可能。

另外，PAICLI 的 inline renderer 在执行期间有 live thinking 区和底部状态栏显示 phase（idle、thinking 等），这些 phase 其实就是状态的雏形，但它们只用于 UI 展示，没有沉淀成可持久化、可校验的状态对象。UI 显示完就丢，执行结束后的复盘拿不到。

## 三、MyCodeAgent 的解法：LoopState 与 RuntimeEvent

MyCodeAgent 的设计核心是把循环状态显式化，把执行过程事件流化，两者用执行 ID 串联。

显式状态用的是 `LoopState`，一个不可变的数据类（frozen dataclass），代表循环在某一刻的快照状态。状态值是有限枚举，比如 INIT、PLANNING、ACTING（工具执行中）、OBSERVING（处理工具结果）、VERIFYING（验证完成，对应第一篇的 Completion Gate）、DONE、FAILED。每次状态转换都伴随一个 `TransitionReason`（为什么转）和可选的 `TerminalReason`（终止原因，仅终态有）。状态转换不是随便的，有一个允许的转换图，非法转换直接报错——比如从 DONE 不能直接跳回 ACTING，这把流程合法性从"运行时碰运气"变成了"定义时校验"。

事件流用的是 `RuntimeEvent`，每次有意义的事情发生都发一个事件：LLM 调用开始/结束、工具调用开始/结束、状态转换、上下文压缩、错误发生、用户介入。每个事件带时间戳、执行 ID、当前 `LoopState`、事件类型、payload。事件被统一收集到一个事件总线，按执行 ID 索引，形成一次执行的完整时间线。

执行 ID 是把状态和事件串起来的钥匙。每次 ReAct 执行开始时生成一个唯一执行 ID，这次执行的所有 `LoopState` 转换和 `RuntimeEvent` 都挂在这个 ID 下。执行结束后，这个 ID 下的所有事件就是这次执行的完整记录，可以回放、可以对比、可以审计。

这套设计的价值有三点。第一，可观测性原生结构化——问"发生了什么"，直接读事件流，不用拼日志。第二，流程合法性可校验——非法状态转换在发生时就被拦，不会让错误流程静默跑下去。第三，执行可回放——事件流 + 状态转换记录了完整轨迹，理论上可以基于事件流重放一次执行（配合 mock 的 LLM 和工具），用于调试和回归测试。

## 四、迁移到 PAICLI 的设计建议

### 4.1 新增的类与职责

建议在 `com.bettercli.agent` 包下新增 `LoopState`、`LoopStateTransition`、`RuntimeEvent`、`ExecutionTrace` 四个核心类型，外加一个 `EventBus`。

`LoopState` 是枚举，定义 PAICLI ReAct 循环的状态值。结合 PAICLI 现有 inline renderer 的 phase（idle、thinking、acting 等），把 UI 展示用的 phase 升级成正式状态：`IDLE`、`THINKING`（LLM 调用中）、`ACTING`（工具执行中）、`OBSERVING`（处理工具结果）、`VERIFYING`（Completion Gate 校验，配合第一篇）、`COMPACTING`（上下文压缩，配合第四篇）、`DONE`、`FAILED`、`ABORTED`（用户中断）。这样 UI 状态和执行状态统一，不再两套。

`LoopStateTransition` 是不可变值对象，记录一次状态转换：from、to、`TransitionReason`、时间戳、触发它的 `RuntimeEvent` 引用。转换合法性由一个静态的允许转换图校验，非法转换抛 `IllegalStateException`。

`RuntimeEvent` 是不可变值对象，记录一次有意义的事件：事件类型（`LlmCallStarted`、`LlmCallFinished`、`ToolCallStarted`、`ToolCallFinished`、`StateChanged`、`ContextCompacted`、`ErrorOccurred`、`UserInterrupt` 等）、时间戳、执行 ID、当前 `LoopState`、payload（类型相关，比如 LLM 事件带 token 用量，工具事件带工具名和结果摘要）。

`ExecutionTrace` 是一次执行的完整记录，按执行 ID 索引，包含起始时间、结束时间、终态、`TerminalReason`、所有 `LoopStateTransition` 和 `RuntimeEvent` 的有序列表。它可序列化（JSON），用于持久化和回放。

`EventBus` 是事件收集和分发中心，支持订阅者（inline renderer 订阅做 UI 更新、audit 订阅做持久化、trace 订阅做完整记录）。它解耦事件产生和消费，让多个消费者各自取所需。

### 4.2 与现有循环和记录的衔接

衔接的核心是把 `while(true)` 里的隐式流程改造成显式状态驱动。改造方式不是推翻循环，而是在循环的关键节点插入状态转换和事件发布。具体：循环开始时发 `ExecutionStarted` 事件、状态转 `IDLE→THINKING`；调 LLM 前发 `LlmCallStarted`、调完发 `LlmCallFinished`；有 toolCalls 时状态转 `THINKING→ACTING`、每个工具调用发 `ToolCallStarted/Finished`、状态转 `ACTING→OBSERVING`；无 toolCalls 时状态转 `OBSERVING→VERIFYING`（配合 Completion Gate）→ `DONE`；出错时状态转 `→FAILED`、发 `ErrorOccurred`；用户中断发 `UserInterrupt`、状态转 `→ABORTED`。

现有的 `AuditLog`、`LlmTraceLogger`、`SessionMessageStore` 改造成 `EventBus` 的订阅者：`AuditLog` 订阅工具事件，`LlmTraceLogger` 订阅 LLM 事件，`SessionMessageStore` 订阅消息事件。这样它们从"各自主动记录"变成"被动消费统一事件流"，记录的时序和对齐天然一致，不再靠时间戳拼凑。inline renderer 也订阅事件流做 UI 更新，把现在散在循环各处的 UI 调用收敛到事件驱动，renderer 只关心事件不关心循环结构。

这个改造的一个直接收益是，inline renderer 的 phase 显示和执行状态彻底统一——现在 phase 是 renderer 自己维护的，可能和循环实际状态有偏差；改成事件驱动后，phase 就是 `LoopState` 的投影，永远一致。

### 4.3 执行回放能力

有了完整的 `ExecutionTrace`，回放能力就有了基础。建议新增一个 `/replay <executionId>` 命令，读取持久化的 trace，按时间顺序回放事件，在 inline renderer 里重现执行过程（包括 live thinking、工具调用、状态转换）。这不执行真实 LLM 和工具，只是重放记录，所以零成本零风险，纯粹用于复盘。

更进阶的是"基于 trace 的回归测试"——把一次成功执行的 trace 作为基线，配合 mock 的 LLM 和工具（mock 按基线 trace 的请求返回对应响应），验证代码重构后执行轨迹是否一致。这能把 PAICLI 现有的"靠手测验证重构没破坏行为"升级为"靠 trace 对比自动验证"，对 PAICLI 这种快速迭代的项目价值很大。

### 4.4 三条路径的统一状态定义

PAICLI 的三条路径（ReAct、Plan-and-Execute、Multi-Agent）循环结构不同，但可以共享一套 `LoopState` 基础定义，各自扩展。ReAct 用基础状态；Plan-and-Execute 在 Task 级别复用基础状态，外加 `PLANNING`、`TASK_DISPATCHED` 等计划级状态；Multi-Agent 在子 Agent 级别复用，外加 `ORCHESTRATING`、`SUBAGENT_RUNNING` 等编排级状态。共享基础状态让跨路径的执行对比成为可能——比如比较同一个任务在 ReAct 和 Plan-and-Execute 下的执行轨迹差异，分析哪种路径更高效。

执行 ID 的层级也对应：主执行 ID 标识一次顶层执行，子执行 ID 标识 Plan 的 Task 或 Multi-Agent 的子 Agent 执行，子执行 ID 挂在主执行 ID 下，形成执行树。回放和审计可以按树结构展开，看清主执行和子执行的关系。

## 五、迭代步骤

这套设计涉及面较广，建议分六步走，每步保持可独立合并。

第一步，定义 `LoopState` 枚举和 `LoopStateTransition`，把 inline renderer 现有的 phase 概念升级成 `LoopState`，UI 显示改为读 `LoopState`。这一步让状态显式化，但暂不强制校验转换合法性，行为基本不变，只是状态来源变了。

第二步，实现 `RuntimeEvent` 和 `EventBus`，在 `Agent.java` 循环关键节点发布事件，但记录器（AuditLog、LlmTraceLogger）暂不改造，仍用原方式记录。这一步让事件流先跑起来，便于观察事件覆盖是否完整。

第三步，把 `AuditLog`、`LlmTraceLogger`、`SessionMessageStore` 改造成 `EventBus` 订阅者，从事件流消费。这一步让记录统一，验证事件流能完整支撑现有记录需求。

第四步，启用状态转换合法性校验，定义允许转换图，非法转换抛异常。这一步开始强制流程合法性，可能暴露一些之前隐式存在的不规范流程，需要配合修复。

第五步，实现 `ExecutionTrace` 持久化和 `/replay` 命令，让执行可回放。

第六步，实现基于 trace 的回归测试框架，配合 mock LLM/工具做轨迹对比。

文档同步：第一步起在 `docs/` 新建 `phase-28-state-machine-tracing.md`，第四步更新 `AGENTS.md` 的架构概览（三条路径状态说明），第五步更新 `/export` 和 `/replay` 命令文档。

## 六、测试方案

第一层，`LoopStateTransition` 合法性测试。验证允许的转换通过、非法的转换抛异常、终态（DONE/FAILED/ABORTED）不能再转出。覆盖所有状态对的两两组合，确保转换图定义无遗漏。

第二层，`EventBus` 测试。验证事件发布、订阅者收到、多订阅者独立、事件顺序保证、订阅者异常不影响其他订阅者。

第三层，循环集成测试。跑一次完整 ReAct（mock LLM 和工具），验证产生的事件流覆盖所有关键节点、状态转换序列合法、`ExecutionTrace` 完整且可序列化。重点验证异常路径（LLM 错误、工具失败、用户中断）的事件和状态转换正确。

第四层，记录器改造测试。验证改造后的 `AuditLog`、`LlmTraceLogger` 从事件流消费后，记录内容和改造前等价（不丢事件、不错序）。

第五层，回放测试。持久化一个 trace，用 `/replay` 回放，验证回放的事件序列和原始一致、UI 重现正确。

第六层，回归测试框架测试。用基线 trace 配合 mock，跑重构前后的代码，验证轨迹一致；故意改一处行为，验证轨迹对比能检出差异。

## 七、风险与权衡

第一个权衡是改造成本。把 `while(true)` 改成事件驱动状态机，涉及 `Agent.java` 核心循环和三个记录器，工作量和风险都不小。缓解办法是分步走、每步可独立合并、每步保持行为不变或基本不变，避免一次性大重构。第一步（状态显式化）和第二步（事件流跑起来）都是低风险的，先做这两步拿到收益，再决定是否继续深入。

第二个权衡是性能开销。每个关键节点发事件、状态转换校验、事件总线分发，都有开销。PAICLI 单轮 ReAct 的事件数量有限（几十个量级），开销可接受，但要在 `EventBus` 实现上注意——同步分发要快，订阅者处理要轻，重活（持久化 trace）可以异步。建议 trace 持久化走异步队列，不阻塞主循环。

第三个权衡是状态粒度。状态太少，可观测性不足；状态太多，转换图复杂、维护成本高。建议从 PAICLI 现有 inline renderer 的 phase 出发，只把已经展示的状态正式化，不盲目增加新状态。状态应该对应"用户能感知到的执行阶段"，而不是代码里的每一步。

第四个权衡是事件 payload 的大小。完整记录 LLM 请求响应、工具结果，trace 文件会很大。建议 payload 区分"摘要"和"完整"两层——trace 里默认存摘要（token 用量、工具名、结果长度），完整内容按需引用现有 `LlmTraceLogger` 和 `AuditLog` 的存储，不重复存。这样 trace 轻量可回放，完整内容仍由专门记录器负责。

第五个权衡是和现有 `SnapshotService` 的关系。snapshot 按轮次做 git 快照，trace 按事件记录执行，两者组织单位不同。建议让 trace 的事件里带上"当前轮次"信息，把 trace 事件和 snapshot 轮次对齐，这样回放时既能看执行轨迹又能恢复某一轮的代码状态，两者协同而非冲突。

第六个权衡是回放和回归测试的真实性。mock LLM/工具按基线 trace 返回响应，只能验证"代码流程没变"，不能验证"LLM 行为没变"（LLM 是非确定性的）。所以基于 trace 的回归测试定位是"流程回归"而非"行为回归"，要和真实 LLM 的手测配合，不能完全替代手测。

## 八、小结

显式状态机与全链事件追踪要解决的，是 PAICLI 在执行可观测性上"隐式状态、分散记录、事后拼凑"的短板。当前循环状态藏在 `while(true)` 的分支里，记录分散在多个独立记录器，要理解一次执行得从多个来源手工对齐，回放和流程合法性校验更无从谈起。

MyCodeAgent 的 `LoopState` 加 `RuntimeEvent` 给了一个清晰参照：把循环状态显式化成有限枚举并校验转换合法性，把执行过程事件流化并用执行 ID 串联，让"发生了什么"有结构化答案、"流程对不对"有定义可校验、"重放执行"有数据可依据。这套设计对 PAICLI 的额外价值在于，它能顺带把 inline renderer 的 phase 显示和执行状态统一，解决现在 UI 状态和循环状态可能偏差的隐患。

落地建议从最低风险的状态显式化和事件流起步，先把可观测性的基础立起来，再逐步深入到记录器统一、合法性校验、回放、回归测试。这套设计是五篇里改造成本最高、见效最慢的，但也是对 PAICLI 长期工程质量提升最根本的——它让 Agent 执行从"黑盒跑一遍"变成"白盒可观测、可校验、可回放"，这种透明度是商业产品走向可靠交付的基石。

## 九、五篇总览

至此，从 MyCodeAgent 借鉴到 PAICLI 的五个最值得的点都已展开。回顾一下它们的内在关系：

第一篇 Completion Gate 解决"循环何时该停"，把完成判定从模型主观陈述变成框架客观校验。第二篇乐观锁与原子写入解决"文件写入怎么不出错"，把并发安全从假设唯一写者变成显式防护。第三篇工具熔断解决"工具坏了怎么办"，把工具失败从无记忆孤立事件变成有健康度的可降级资源。第四篇模型错误恢复解决"LLM 调用失败怎么应对"，把错误处理从一刀切变成分类恢复，并打通响应式上下文压缩。第五篇状态机与事件追踪解决"执行过程怎么看清"，把循环从隐式 while 变成显式可观测可回放的状态机。

这五点串起来，其实是在补齐 PAICLI 作为 Agent 框架的"运行时健壮性"这一整面：完成判定、并发安全、工具韧性、错误恢复、可观测性，每一项都是 Agent 从"能跑"走向"可靠跑"的关键一环。它们彼此也有协同——Completion Gate 的证据失效依赖工具的 mutating 标记，乐观锁的冲突信号能喂给 Completion Gate，熔断器的健康度能注入 system prompt，错误恢复的响应式压缩和状态机的 COMPACTING 状态对应，事件追踪能把前四者的行为都记录成可回放轨迹。建议落地时按第一到第五的顺序推进，让协同关系自然生长，而不是各自孤立上线。








