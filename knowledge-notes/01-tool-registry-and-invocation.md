# 工具注册与调用：面试级知识帖

> 面向对象：第一次了解 BetterCLI 开源项目的同学
> 主题：Agent 的工具系统是怎么设计的——选型、架构、流程、取舍
> 写法：模拟面试官会问的"方案级"问题，不抠代码细节

---

## Q1：为什么需要一个集中的 ToolRegistry？能不能让每个工具自己注册到 LLM 上下文里？

**回答：**

选集中注册，主要是为了把"工具的生命周期"和"工具的使用方"解耦。

BetterCLI 有三条执行路径——ReAct、Plan-and-Execute、Multi-Agent——它们都需要调用工具。如果每个工具自己向 LLM 暴露，那三条路径各自要维护一份工具列表、各自做安全校验、各自处理并行，重复且容易不一致。集中注册后，`ToolRegistry` 成为一个**能力总线**：谁要调工具都走同一个入口，安全策略、审计、并行调度都在这一层统一做。

另一个考虑是**动态扩展**。MCP 工具是运行期才接入的（连上 MCP server 后才知道有哪些工具），如果工具分散在各处，动态注册会很难管理。集中在一个 map 里，内置工具启动时注册，MCP 工具运行期注册，对调用方完全透明。

代价是 `ToolRegistry` 这个类比较大（2000+ 行），承担了注册、执行、安全、审计、并行多职责。项目里也意识到了，所以把代码搜索单独抽成了 `CodeSearchEngine` 接口，`PathGuard` / `CommandGuard` 也独立成类。这是一个"中心化但内部有拆分"的折中。

---

## Q2：工具调用走的是 ReAct 还是 Plan-and-Execute？还是两者都有？

**回答：**

两者都有，而且还有第三条 Multi-Agent 路径。这是 BetterCLI 架构上比较有意思的一个点——**同一个工具底座，三条执行路径共享**。

| 路径 | 入口 | 触发 | 适合场景 |
|------|------|------|---------|
| ReAct | `Agent.java` | 默认 | 边想边做，单步工具调用 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` | 先规划 DAG 再执行，复杂多步任务 |
| Multi-Agent | `AgentOrchestrator.java` | `/team` | 多角色协作，SubAgent 分工 |

三条路径都最终走 `ToolRegistry.executeTools()` 调用工具，所以工具本身不关心是被谁调用的。这个设计的好处是**工具实现一次，三种执行模式都能用**；安全策略也只需要在 ToolRegistry 这一层做一次。

选型上的取舍：ReAct 灵活但容易跑偏，Plan 模式先规划再执行能减少无效调用但规划本身有成本，Multi-Agent 适合大任务但协调开销大。让用户自己选触发方式，而不是一种模式打天下。

---

## Q3：工具执行结果是怎么回到 LLM 的？整个闭环长什么样？

**回答：**

这是一个标准的 ReAct 闭环，但有几个关键设计点：

```
用户输入
   ↓
PromptAssembler 组装 system prompt + 工具定义 + 上下文
   ↓
LLM 推理 → 输出文本 + 可选的 tool_call
   ↓
Agent 解析 tool_call → 构造 ToolInvocation 列表
   ↓
ToolRegistry.executeTools() 并行执行
   ↓
结果包装成 tool_result 消息回灌 conversation history
   ↓
LLM 基于结果继续推理（循环）或输出最终回答
```

几个架构决策值得注意：

1. **工具结果是字符串**，不是结构化对象。LLM 读字符串比读 JSON 容易，而且不同 provider 对结构化 tool_result 的支持不一致。统一成字符串是最兼容的做法。
2. **结果回灌走 conversation history**，而不是单独的 channel。这样压缩、截断、上下文管理都能复用同一套机制。
3. **图片是例外**：MCP 工具可能返回图片，所以引入了 `ToolOutput`（文本 + 图片 parts），在回灌时图片走多模态 content part，文本走普通字符串。这是为了支持像截图、图表这类场景。

---

## Q4：多个工具调用是怎么调度的？为什么选这个方案？

**回答：**

选的是**固定大小线程池 + 批次超时 + 顺序返回**，不是异步回调，也不是反应式流。

流程：
- LLM 一轮可能输出多个 tool_call（比如同时读 3 个文件）
- `executeTools()` 创建 `min(数量, 4)` 的线程池，每个工具一个 task
- `invokeAll` 等待全部完成或超时（默认 90 秒）
- 结果按 LLM 输出的原始顺序收集，即使并行执行完成顺序不同
- 超时的任务被取消，已完成的保留结果

为什么选这个方案而不是异步：

1. **ReAct 是同步闭环**——LLM 必须看到所有工具结果才能继续推理，异步回调没有意义，反正要等齐。
2. **顺序很重要**——回灌 message history 时要和 LLM 的 tool_call 顺序对齐，否则部分 provider 会报错。
3. **超时是硬需求**——工具可能卡死（比如 `execute_command` 跑了个死循环），必须有强制取消。
4. **并发度上限 4**——避免一次发起几十个文件读把磁盘 IO 打满，也避免对 MCP server 限流。

这个方案的代价是线程池每次都创建销毁（`shutdownNow` 在 finally 里），有轻微开销。但工具调用频率不高（一轮 LLM 才一次），这点开销可以接受。

---

## Q5：MCP 工具和内置工具是怎么融合的？为什么不做成两套？

**回答：**

融合点在 `ToolRegistry` 内部维护两个 map，但对外暴露一个统一的工具列表。

- `tools`：所有工具（内置 + MCP），LLM 看到的就是这个
- `mcpTools`：仅 MCP 工具，保存原始 invoker

执行时先查 `mcpTools`，命中走 MCP 调用路径（JSON 透传给 MCP client）；没命中走内置工具路径（JSON 转成 `Map<String, String>`）。

为什么不做成两套：

1. **LLM 不该感知差异**——对 LLM 来说工具就是工具，不需要知道是内置的还是 MCP 的。统一列表让 LLM 的选择逻辑更简单。
2. **安全策略统一**——不管是内置还是 MCP，危险操作都要走 HITL 和审计。两套系统就要做两遍安全。
3. **调用方无感**——Agent / PlanExecuteAgent / SubAgent 都只调 `executeTool(name, args)`，不关心工具来源。

但有一个**关键区别**保留了：参数传递方式不同。内置工具转成 `Map<String, String>`（简单够用），MCP 工具保留原始 JSON（因为 MCP 参数可能是嵌套对象、数组，转字符串 map 会丢结构）。这是"统一接口、差异化实现"的典型做法。

MCP 工具命名用 `mcp__{server}__{tool}` 前缀，避免和内置工具重名，也方便审计日志按前缀筛选。

---

## Q6：安全策略放在哪一层？为什么？

**回答：**

放在**工具执行层**（ToolRegistry），不是 Agent 层，也不是工具内部。

拦截链是：`HITL → ToolRegistry → PathGuard/CommandGuard → AuditLog`

为什么选这个位置：

1. **所有路径都受保护**——ReAct / Plan / Multi-Agent 都调 ToolRegistry，安全策略放这里一处生效，不用三条路径各自实现。
2. **工具内部不用关心安全**——`read_file` 的 lambda 只负责读文件，路径校验在它之前的 `PathGuard.resolveSafe()` 已经做完。工具实现者不需要每次都写 `if (path.startsWith(".."))`。
3. **HITL 在最前面**——用户审批是可协商的（可以批准/拒绝），策略层是硬护栏（不可协商）。顺序不能反，否则用户批准了策略拒绝的操作就出事了。
4. **审计在最后**——无论 allow/deny/error 都记录，便于事后追溯。

这个设计的隐含假设是：**工具本身是可信的**（项目自己写的内置工具），所以安全检查放在调用入口就够了。如果是第三方插件式工具，可能需要在工具内部也做校验。MCP 工具属于半可信，所以浏览器类 MCP 工具额外加了 `BrowserGuard`。

---

## Q7：工具定义是怎么让 LLM 理解的？跨 provider 怎么兼容？

**回答：**

用 **JSON Schema 描述参数 + 统一的 Tool 抽象**，然后由各个 LLM 客户端自己适配成 provider 认识的格式。

`ToolRegistry.getToolDefinitions()` 返回 `List<LlmClient.Tool>`，每个 Tool 有 name / description / parameters(JSON Schema)。这个结构是 provider 无关的。具体的 `GLMClient` / `DeepSeekClient` / `KimiClient` / `StepClient` / `AgnesClient` 各自把这个列表序列化成自己 API 的函数调用格式。

选 JSON Schema 的原因：

1. **OpenAI 函数调用的事实标准**——大部分兼容 provider 都认这个格式。
2. **LLM 训练时见过**——主流模型对 JSON Schema 参数的理解已经很好，不需要额外训练。
3. **类型自描述**——string/integer/boolean + required 数组，LLM 能自己推断该传什么。

兼容性上的难点不在工具定义，而在**工具结果回灌**。不同 provider 对 `tool_result` 消息的 role 和字段命名不一样（有的叫 `tool`，有的叫 `function`），这部分由各 Client 自己处理。BetterCLI 的做法是把结果统一成字符串，减少 provider 差异。

---

## Q8：这个工具系统最大的设计风险是什么？

**回答：**

从架构看，有几个潜在风险点：

1. **ToolRegistry 职责过重**——注册、执行、安全、审计、并行都在一起，2000+ 行。如果继续加工具，维护成本会上升。项目里已经把代码搜索抽成 `CodeSearchEngine` 接口缓解，但核心执行路径还是集中在一个类。

2. **`Map<String, String>` 的参数模型有上限**——内置工具参数只能是字符串，不能直接传数组或嵌套对象。目前够用（文件路径、命令、查询都是字符串），但如果未来内置工具需要复杂参数（比如批量操作传文件列表），就要改架构。

3. **并行执行的线程池不复用**——每次 `executeTools` 都新建线程池再 shutdown，高频调用时有开销。不过 ReAct 一轮才调一次，实际影响很小。

4. **安全策略是单层的**——`PathGuard` 只校验路径在项目根内，不校验文件内容。如果 LLM 写一个恶意脚本到项目内的 `.bashrc`，PathGuard 不会拦。这是"围栏 vs 沙箱"的取舍——BetterCLI 选的是围栏，不做内容审计。

这些风险里，第 1 和第 2 个是架构层面的，会随项目演进逐渐暴露；第 3、4 个是已知取舍，目前可接受。

---

## 学习要点速记

| 维度 | 选型 |
|------|------|
| 注册方式 | 集中注册（ToolRegistry），内置启动注册 + MCP 运行期注册 |
| 执行路径 | 三条共享一个工具底座（ReAct / Plan / Multi-Agent） |
| 闭环模型 | 标准 ReAct，工具结果字符串回灌 conversation history |
| 并行调度 | 固定线程池（≤4）+ 批次超时 + 顺序返回 |
| MCP 融合 | 统一工具列表，差异化参数传递（Map vs JSON 透传） |
| 安全层 | 工具执行层，HITL → 策略 → 审计，三条路径共用 |
| LLM 兼容 | JSON Schema 描述工具，各 Client 适配 provider 格式 |
| 主要风险 | ToolRegistry 膨胀、参数模型单一、围栏非沙箱 |

---

## 延伸阅读路径

1. `ToolRegistry.java`——看注册和执行的主干
2. `Agent.java` / `PlanExecuteAgent.java` / `AgentOrchestrator.java`——看三条路径怎么调工具
3. `PathGuard.java` / `CommandGuard.java` / `HitlToolRegistry.java`——看安全链
4. `McpServerManager.java`——看 MCP 工具怎么动态进来
5. `LlmClient.java` + 各 `*Client.java`——看工具定义怎么适配到不同 provider
