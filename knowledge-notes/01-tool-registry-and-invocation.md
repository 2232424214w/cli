# 工具注册与调用：Agent 到底是怎么"动手干活"的

> 写给第一次翻开 BetterCLI 源码的同学。这一篇聊的是工具系统——Agent 自己不会读文件、不会跑命令，真正干活的是工具。那这些工具是怎么冒出来的、LLM 怎么知道它们、调用时又发生了什么？我们从这里开始。

---

## 先说说这个模块在干嘛

如果你用过 Claude Code 或者类似的 Agent 产品，你会发现 Agent 回复你的时候，中间会穿插一些"我在读文件""我在搜代码"的动作。这些动作背后不是 LLM 直接伸手去碰磁盘，而是 LLM 输出一个"工具调用"的意图，由外部代码替它执行，再把结果喂回去。

BetterCLI 里管这件事的就是 `ToolRegistry`，它住在 `src/main/java/com/bettercli/tool/ToolRegistry.java`。你可以把它想成一个"能力总线"：所有工具都挂在上面，谁要用工具都走它，安全检查、并行调度、审计日志也都在这一层做掉。

但 `ToolRegistry` 不是孤军奋战。围绕它有一圈各司其职的类：`PathGuard` 和 `CommandGuard` 负责安全护栏，`HitlToolRegistry` 负责人工审批，`AuditLog` 负责审计记录，`McpServerManager` 和 `McpClient` 负责把外部 MCP 工具动态接进来，`CodeSearchEngine` 和它的实现 `RipgrepCodeSearchEngine` 负责代码搜索这一摊。调用方这边，`Agent`、`PlanExecuteAgent`、`AgentOrchestrator` 三条执行路径都依赖它。这一篇我们就顺着"工具怎么来 → LLM 怎么看到 → 怎么执行 → 怎么保证安全"这条线，把这些类的角色捋清楚。

---

## 它能做什么：先看工具箱里有哪些家伙

打开 `ToolRegistry` 的构造函数，你会看到一串 `registerXxxTools()` 调用。这就是工具的注册时机——项目一启动，内置工具就全注册好了。我按功能给它们分了组，方便你建立直觉。

**最基础的是文件操作**，一共 5 个。`read_file` 能按行切片读文件，传 offset 和 limit 就只读一段，避免大文件塞爆上下文。`write_file` 写文件有 5MB 上限，还会自动建父目录，写完顺手触发 LSP 诊断。`list_dir` 列目录内容，刻意保持极简，逼 Agent 用更精确的工具探索。`glob_files` 按 glob 模式找文件，比如 `**/*Service.java`，内部用 `Files.walkFileTree` 配合 `PathMatcher` 实现，还会自动跳过 `.git`、`target`、`node_modules` 这些目录。`grep_code` 按关键字或正则搜代码，优先调用本机的 ripgrep，不可用时回退到 Java 扫描——这部分逻辑被抽到了 `CodeSearchEngine` 接口和 `RipgrepCodeSearchEngine` / `JavaCodeSearchEngine` 两个实现里。这五个是 Agent 理解代码库的主力，对标 Claude Code 的实时探索思路。

**接着是 Shell 和项目创建**。`execute_command` 执行 shell 命令，默认 60 秒超时，输出有 8000 字符上限。`create_project` 能一键生成 Java/Python/Node 项目骨架。

**代码检索分两层**。`grep_code` 是精确的字符串/正则定位，`search_code` 是 RAG 语义检索——后者基于向量库做自然语言查询，适合"用户登录是怎么实现的"这种模糊问题。两个工具分工明确，不是谁替代谁。`search_code` 背后是 `rag/` 包里的 `CodeRetriever`、`CodeIndex`、`VectorStore` 一整套，但工具调用方不用关心这些，只管调 `search_code`。

**联网能力**有 `web_search` 和 `web_fetch`。前者支持 SerpAPI 和 SearXNG 两种 provider，由 `SearchProviderFactory` 决定用哪个；后者抓 URL 转 Markdown，遇到 SPA 或防爬墙会走 Chrome DevTools MCP 兜底。浏览器还有三个工具管理登录态复用：`browser_connect` / `browser_disconnect` / `browser_status`，背后是 `browser/` 包里的 `BrowserConnector` 和 `BrowserGuard`。

**记忆系统是重头戏**，工具数量最多。`save_memory` 是用户明确要求时才保存的长期记忆；`read_better_md` / `suggest_better_md` 管项目级 BETTER.md（对标 Claude Code 的 CLAUDE.md），背后是 `prompt/` 包里的 `ProjectMemoryLoader`；`agent_memory_search` / `save` / `update` / `delete` 是 Agent 自主维护的事实记忆（对标美团 1024 Agent 的 agent_memory 表），背后是 `memory/` 包里的 `AgentMemoryStore` 和 `SqliteAgentMemoryStore`；`session_search` 检索历史会话消息，背后是 `SessionMessageStore` 和 `SqliteSessionMessageStore`。这几类记忆分工不同，后面会有专门一篇讲。

**最后是 Skill 和快照**。`load_skill` 把 Skill 指引加载到下一轮上下文，背后是 `skill/` 包里的 `SkillRegistry` 和 `SkillContextBuffer`。`revert_turn` 恢复 Side-Git 快照，属于高危操作必须审批，背后是 `snapshot/` 包里的 `SnapshotService`。

数下来内置工具 18 个。但工具箱不是封闭的——BetterCLI 还支持通过 MCP（Model Context Protocol）接入外部工具。`McpServerManager` 启动 MCP server 后，`McpClient` 完成握手，拿到工具描述 `McpToolDescriptor`，然后调用 `ToolRegistry` 的 `registerMcpTool` 把它们动态注册进来，命名格式是 `mcp__{server}__{tool}`，比如 `mcp__step_search__web_search`。对 LLM 来说，MCP 工具和内置工具长得一模一样，都出现在同一个工具列表里。检测到 `STEP_API_KEY` 时还会自动内置一个 `step_search` 远程 MCP。

---

## 架构长什么样：一个注册中心，两条执行路径

了解了工具箱，我们看它们怎么被组织起来。

`ToolRegistry` 内部其实维护着两个 map。一个叫 `tools`，装的是所有 LLM 可见的工具（内置 + MCP），`getToolDefinitions()` 返回的就是它；另一个叫 `mcpTools`，只装 MCP 工具，保存它们的原始 invoker。执行的时候先查 `mcpTools`，命中就走 MCP 路径，没命中才走内置路径。

为什么要分两条路径？因为参数传递方式不一样。内置工具的参数会被转成 `Map<String, String>` 再交给注册时的 lambda——这样 lambda 签名简单，绝大多数工具（路径、命令、查询都是字符串）写起来很轻。但 MCP 工具不能这么转，因为 MCP 参数可能是嵌套对象、数组，转成字符串 map 会丢结构。所以 MCP 路径直接把 JSON 字符串透传给 `McpClient.callTool`，保留原始类型。

这是"统一接口、差异化实现"的典型做法：对外都是 `executeTool(name, json)`，内部按工具来源分两条路。LLM 完全不感知这个差异。你可能注意到，这个设计有一个隐含的取舍：内置工具参数只能是字符串，不能直接传数组或嵌套对象。目前够用，但如果未来某个内置工具需要复杂参数（比如批量操作传一个文件列表），就得动架构。这是一个已知的上限。

这里还有个细节值得提：MCP 工具虽然和内置工具共用一个 `tools` map，但它的注册走的是单独的入口 `registerMcpTool` / `replaceMcpToolsForServer`，而不是和内置工具混在一起。这样 MCP server 重连、工具列表更新时可以整批替换，不会误伤内置工具。`replaceMcpToolsForServer` 会先按前缀 `mcp__{server}__` 清掉该 server 的旧工具，再注册新的，保证一致性。

---

## 为什么是集中注册，而不是各工具自己冒头

你可能会问：为什么搞一个大类来集中管，而不是让每个工具自己注册到 LLM 上下文里？

主要原因是 BetterCLI 有三条执行路径——`Agent`（ReAct，默认）、`PlanExecuteAgent`（`/plan` 触发）、`AgentOrchestrator`（`/team` 触发）——它们都需要调工具。如果工具分散在各处，三条路径各自要维护一份工具列表、各自做安全校验、各自处理并行，重复且容易不一致。集中注册后，谁要调工具都走 `ToolRegistry` 这一个入口，安全策略、审计、并行调度都在这一层统一做一次。

MCP 工具的动态接入也受益于集中注册——运行期往同一个 map 里 put 就行，对调用方完全透明。`McpServerManager` 在后台拉起 server、`McpClient` 握手拿工具列表、再调 `ToolRegistry` 注册，整个流程调用方一行代码都不用改。

代价是 `ToolRegistry` 职责确实重，承担了注册、执行、安全、审计、并行多件事。项目也意识到了，所以把代码搜索单独抽成 `CodeSearchEngine` 接口（`RipgrepCodeSearchEngine` 优先 + `JavaCodeSearchEngine` 回退），`PathGuard` / `CommandGuard` 也独立成类放在 `policy/` 包，`AuditLog` 也在那个包里。这是一个"中心化但内部有拆分"的折中，不是一锅炖。换句话说，`ToolRegistry` 是门面，真正的活儿被分到了几个专职类里。

---

## 工具怎么让 LLM 看到：JSON Schema 是桥梁

工具注册好了，LLM 怎么知道有哪些工具可用？这中间靠的是 JSON Schema。

每个工具注册时会用 `createParameters(...)` 生成一段 JSON Schema，描述参数名、类型、是否必填。比如 `read_file` 的 schema 大概长这样：`path` 是必填字符串，`offset` 和 `limit` 是可选整数。这段 schema 连同工具名和描述，被包装成 `LlmClient.Tool` 这个 record 对象。

当 `Agent` 要调 LLM 时，`ToolRegistry.getToolDefinitions()` 把所有 Tool 收集成列表，交给具体的 LLM 客户端。`LlmClient` 是统一接口，各 provider 有自己的实现：`GLMClient`、`DeepSeekClient`、`KimiClient`、`StepClient`、`AgnesClient`、`FreeLlmApiClient`、`XfyunMaaSClient`。每个客户端再把这个列表序列化成自己 provider 认识的函数调用格式——OpenAI 系是一套字段名，别的 provider 可能略有不同，但结构一致。`LlmClientFactory` 负责根据配置决定实例化哪个客户端。

选 JSON Schema 不是随便选的。它是 OpenAI 函数调用的事实标准，大部分兼容 provider 都认；而且主流模型训练时见过这个格式，理解参数类型不需要额外训练。兼容性的真正难点不在工具定义，而在工具结果回灌——不同 provider 对 `tool_result` 消息的 role 和字段命名不一样，这部分由各 Client 自己处理。BetterCLI 的做法是把结果统一成字符串，减少 provider 差异带来的麻烦。

---

## 调用闭环：一次工具调用的完整旅程

现在我们把整条链串起来看一次。

用户输入后，`PromptAssembler`（在 `prompt/` 包里）组装 system prompt，工具定义也一起注入。LLM 拿到上下文开始推理，如果判断需要动手，就会输出一个或多个 `tool_call` 块，每个块里有工具名和 JSON 参数。

`Agent` 解析这些 tool_call，构造 `ToolInvocation` 列表，交给 `ToolRegistry.executeTools()`。这里有个细节：如果只有单个工具，直接同步执行；如果有多个，就开一个固定大小线程池（最多 4 个并发），每个工具一个 task，用 `invokeAll` 等待，超时 90 秒强制取消。结果按 LLM 输出的原始顺序收集返回——这点很重要，因为回灌 message history 时要和 tool_call 顺序对齐，否则部分 provider 会报错。返回类型是 `ToolExecutionResult`，里面除了结果文本，还带耗时、是否超时、图片 parts 等信息。

为什么选线程池而不是异步回调或反应式流？因为 ReAct 是同步闭环——LLM 必须看到所有工具结果才能继续推理，异步回调没有意义，反正要等齐。并发度上限 4 是为了避免一次发起几十个文件读把磁盘 IO 打满，也避免对 MCP server 限流。代价是线程池每次创建销毁有轻微开销，但工具调用频率不高（一轮 LLM 才一次），这点开销可以接受。另外还有个 `CancellationContext`，用户中途取消时所有工具调用会快速感知到并退出，不用等超时。

执行结果包装成 `ToolOutput`——通常是纯文本字符串，但 MCP 工具可能返回图片，所以 `ToolOutput` 也能带图片 parts。结果回灌到 conversation history，成为下一轮 LLM 推理的输入。这就是标准的 ReAct 闭环：思考 → 调工具 → 看结果 → 继续思考。

---

## 安全这一关：围栏，不是沙箱

工具能动手，就一定要有安全护栏。BetterCLI 的安全策略放在工具执行层（`ToolRegistry`），而不是 Agent 层。这样三条执行路径都受保护，不用各自实现一遍；工具内部也不用关心安全——`read_file` 的 lambda 只管读文件，路径校验在它之前的 `PathGuard.resolveSafe()` 已经做完。

拦截链是固定的：`HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard → AuditLog`。

`HitlToolRegistry` 在最前面，是可协商的——用户能批准或拒绝。它检查是否需要审批，需要的话弹 `TerminalHitlHandler`（终端模式）或 `RendererHitlHandler`（inline 模式），用户没批准直接返回拒绝。`ApprovalPolicy` 决定哪些工具算危险（`DANGEROUS_TOOLS` 集合），`SwitchableHitlHandler` 负责在不同渲染模式下切换审批入口。

策略层是硬护栏，不可协商。`PathGuard` 强制文件路径在项目根内，解决三类越界：绝对路径直接逃出项目根（LLM 给 `/etc/passwd`）、相对路径用 `..` 穿越、符号链接逃逸。它的实现有个巧妙之处——对于不存在的路径（write_file 创建新文件场景），会向上找最近的存在祖先做 `toRealPath`，再把剩余段接回，这样仍能识别"路径中段是个指向外部的软链"。`CommandGuard` 则是命令黑名单，fast-fail 掉 `curl ... | sh` 这类已知危险模式，它在 `execute_command` 真正调用 `ProcessBuilder` 之前就拦住。

顺序不能反——如果策略层在 HITL 前面，用户批准了策略拒绝的操作就出事了；如果策略层在 HITL 后面但不强制，用户能批准越界路径，围栏就形同虚设。所以设计上 HITL 可协商，策略不可协商，各司其职。

最后是审计。`AuditLog` 记录每次危险工具调用的 allow/deny/error，`write_file` / `execute_command` / `create_project` / `revert_turn` 强制审计，所有 `mcp__*` 前缀的 MCP 工具也强制审计。审计条目存在 `AuditLog.AuditEntry` 里，便于事后追溯。

这里有个重要的取舍：BetterCLI 选的是**围栏，不是沙箱**。`PathGuard` 只校验路径在项目根内，不校验文件内容。如果 LLM 写一个恶意脚本到项目内的 `.bashrc`，`PathGuard` 不会拦。沙箱（容器/VM）在路线图里但未交付。围栏的好处是轻量、无依赖、跨平台；代价是安全性弱于沙箱，依赖用户在可信项目里使用。

---

## 这个设计的风险和边界在哪

聊完了架构，也得说说它的软肋，这样你用的时候心里有数。

第一个是 `ToolRegistry` 职责过重。它承担了注册、执行、安全、审计、并行多件事，虽然内部把代码搜索、路径围栏、命令围栏拆成了独立类，但核心执行路径仍集中在一个类里。如果继续加工具，维护成本会上升。这是中心化设计的固有代价。

第二个是参数模型有上限。内置工具参数只能是 `Map<String, String>`，不能直接传数组或嵌套对象。目前够用，但未来如果内置工具需要复杂参数，就得改架构。MCP 工具因为保留 JSON 透传，没这个问题——这也是为什么复杂能力优先走 MCP 接入而不是做成内置工具。

第三个是围栏非沙箱，前面提过了——不校验内容，依赖项目可信。

第四个是跨平台 shell 适配缺失。`execute_command` 硬编码 `bash -c`，Windows 上没有 bash 会直接失败。这是已知缺口，没做 `cmd.exe` / `powershell` 分支。纯文件操作（读写、glob、grep）在 Windows 上能跑，因为底层是 Java NIO.2，但一旦 Agent 想执行 shell 命令就会撞墙。剪贴板图片读取倒是做了 Mac 适配（`ClipboardImage` 里走 osascript），但 `execute_command` 这块还没补。

最后说清楚这个模块的边界——它不负责什么。它不负责决策（调哪个工具由 LLM 决定），不负责上下文管理（工具结果回灌后的压缩、截断由 `memory/` 包里的 `MemoryManager` 和 `ConversationHistoryCompactor` 管），也不负责工具的具体业务实现（每个工具的 lambda 自己写，`ToolRegistry` 只调度）。它就是一个纯执行器加安全关卡。

---

## 想继续深挖，按这个顺序读源码

1. `ToolRegistry.java`——注册和执行的主干，重点看 `registerFileTools` 和 `executeTools`
2. `Agent.java`——ReAct 主循环，看它怎么调 `ToolRegistry`
3. `PlanExecuteAgent.java` / `AgentOrchestrator.java`——另两条路径怎么共享同一个工具底座
4. `PathGuard.java` / `CommandGuard.java`——安全护栏的实现
5. `HitlToolRegistry.java` / `TerminalHitlHandler.java`——人工审批怎么弹
6. `McpServerManager.java` / `McpClient.java`——MCP 工具从哪动态进来
7. `CodeSearchEngine.java` / `RipgrepCodeSearchEngine.java`——代码搜索的引擎抽象和回退
8. `LlmClient.java` 加上各个 `*Client.java`——工具定义怎么适配到不同 provider

读的时候带着一个问题：**为什么这一层要这样切？** 你会发现大部分答案都在"解耦"和"统一"这两个词里。
