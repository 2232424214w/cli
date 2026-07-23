# 04 模型错误分类与恢复策略

## 一、背景：LLM 调用失败的"一刀切"处理

Agent 框架和 LLM 交互时，错误处理往往被简化成"捕获异常、返回错误字符串"，但 LLM 的错误其实有截然不同的几类，每类需要不同的恢复策略，一刀切处理会让 Agent 在错误面前表现得要么粗暴、要么无效。

PAICLI 的 `Agent.java` 主循环里，LLM 调用 `LlmClient.chat` 如果抛异常，通常是被 `catch(IOException)` 兜住，返回一个泛化的错误字符串，循环可能终止或重试。这种处理把所有错误都当成"一次失败的调用"，没有区分错误性质。但实际场景里，LLM 错误至少有这么几类性质完全不同的情况：上下文超长（prompt 太长，模型根本处理不了）、空响应（模型返回了空内容，可能是被安全过滤或限流）、输出超限（模型输出超过 max_tokens 被截断）、网络瞬断（请求没到或响应断了）、鉴权失败（API key 失效或额度耗尽）。

这几类错误的正确反应完全不同。上下文超长应该触发上下文压缩（PAICLI 有 `ConversationHistoryCompactor`，但没和错误自动联动），重试同样的 prompt 必然还是超长；空响应应该换种问法或提示模型继续，而不是原样重试；输出超限应该提醒模型分步输出或调大 max_tokens，重试只会再次截断；网络瞬断才值得指数退避重试；鉴权失败重试毫无意义，应该直接报错让用户修配置。

把这些错误混为一谈，会导致 Agent 在上下文超长时还在傻重试（必然失败）、在网络瞬断时直接放弃（本可重试成功）、在输出超限时反复截断（永远拿不到完整答案）。MyCodeAgent 在这件事上做了一套错误分类和针对性恢复策略，值得 PAICLI 借鉴。

## 二、PAICLI 现状：泛化捕获，无分类恢复

看清楚 PAICLI 现在怎么处理 LLM 错误。

在 `AbstractOpenAiCompatibleClient.chat` 方法里，HTTP 调用失败会抛 `IOException`，错误信息里拼了状态码和响应体。这个异常往上抛到 `Agent.java`，被主循环的 catch 兜住。兜住之后做什么，在不同路径里不太一样：ReAct 路径通常是把异常信息转成一条 assistant 消息或直接打印错误，然后要么终止循环、要么让下一轮重试。无论哪种，都没有对错误性质做判断——422（上下文超长）和 502（网关错误）被一视同仁。

PAICLI 其实已经具备了针对性恢复所需的基础设施，但它们是割裂的、需要手动触发的。`ConversationHistoryCompactor` 能压缩上下文，但只在上下文 token 估算超阈值时自动触发，不会在 LLM 返回"prompt too long"错误时被动触发。`/compact` 命令能让用户手动压缩，但 Agent 自己不会在遇到超长错误时主动调用。这意味着，当上下文因为某轮工具结果暴涨而超长时，Agent 要么靠 token 估算提前压缩（估算可能不准），要么在 LLM 报错后干瞪眼。

DeepSeek 的 `reasoning_content` 回灌、HTTP/1.1 强制、图片内容替换等 provider 适配，说明 PAICLI 对单个 provider 的 quirks 是有感知的，但这些适配是"请求构造层"的，不是"错误恢复层"的。请求构造时知道 DeepSeek 不支持图片，但 DeepSeek 返回错误时，框架不会因为"这是 DeepSeek 特有的限流"而采取不同策略。

另外，PAICLI 的重试逻辑也偏简单。网络瞬断时，模型如果原样重试，往往能成功，但 PAICLI 没有自动重试机制，全靠模型在下一轮自己再调一次——而模型往往不会原样重试，它会换参数或换说法，把本来能重试成功的瞬断变成一次失败的任务推进。

## 三、MyCodeAgent 的解法：错误分类与针对性恢复

MyCodeAgent 把 LLM 错误显式分类成几个枚举值，每类配一种恢复策略，由一个统一的错误处理器调度。

它定义的错误类别大致包括：`PROMPT_TOO_LONG`（上下文超长，通常是 422 或特定错误码）、`EMPTY_RESPONSE`（模型返回空内容）、`MAX_OUTPUT`（输出被 max_tokens 截断）、`RATE_LIMITED`（限流，429）、`NETWORK_ERROR`（网络瞬断）、`AUTH_ERROR`（鉴权失败，401/403）、`PROVIDER_ERROR`（provider 内部错误，5xx）。每个类别对应一种恢复动作。

`PROMPT_TOO_LONG` 的恢复是触发响应式上下文压缩——立即调用 compaction 压缩 conversationHistory，然后重试。这比预估阈值更可靠，因为它是基于 provider 实际反馈的，而不是框架估算。压缩后重试如果还超长，再压缩一轮或报错。

`EMPTY_RESPONSE` 的恢复是注入一条提示让模型继续，或者换一种更直接的问法重试。空响应往往是模型"卡住"或被安全过滤，换个问法常能绕过。

`MAX_OUTPUT` 的恢复是提醒模型分步输出（"请继续从上次中断处输出"），或调大 max_tokens 重试。直接重试同样的 prompt 只会再次截断。

`RATE_LIMITED` 和 `NETWORK_ERROR` 的恢复是指数退避重试——等几十毫秒到几秒，重试同样的请求。这两类是瞬态错误，重试有意义。

`AUTH_ERROR` 和持续的 `PROVIDER_ERROR` 不重试，直接抛出明确错误让用户介入，因为重试无意义，只会浪费时间。

这套分类恢复的价值在于，它让 Agent 在错误面前有"判断力"——不是无脑重试也不是无脑放弃，而是根据错误性质采取最可能成功的动作。尤其是响应式上下文压缩，把"上下文管理"从预估式变成了反馈式，可靠性大幅提升。

## 四、迁移到 PAICLI 的设计建议

### 4.1 新增的类与职责

建议在 `com.bettercli.llm` 包下新增 `LlmError`、`LlmErrorClassifier`、`LlmErrorRecovery` 三个类型。

`LlmError` 是一个带类别的异常或值对象，封装原始错误信息和分类后的类别。它包含：`category`（`LlmErrorCategory` 枚举）、`httpStatus`（可选）、`providerMessage`（provider 返回的原始错误信息）、`retryable`（是否值得重试）、`cause`（原始异常）。把现在到处抛的 `IOException` 升级成 `LlmError`，让上层能拿到结构化信息。

`LlmErrorClassifier` 负责把原始异常或 HTTP 响应分类成 `LlmErrorCategory`。分类逻辑基于 HTTP 状态码、provider 错误信息关键词、provider 类型。比如 422 + "context length" 关键词 → `PROMPT_TOO_LONG`；429 → `RATE_LIMITED`；空响应体 → `EMPTY_RESPONSE`；finish_reason 是 length → `MAX_OUTPUT`；401/403 → `AUTH_ERROR`。分类器要支持 provider 扩展，因为不同 provider 的错误信息格式不同（DeepSeek、GLM、Kimi 的错误码和文案各异），建议用策略模式，每个 provider 注册自己的分类规则。

`LlmErrorRecovery` 负责根据类别执行恢复动作。它对外暴露 `recover(error, context)`，返回一个 `RecoveryAction`：可能是 `RETRY`（重试，可能带退避）、`COMPACT_AND_RETRY`（压缩后重试）、`HINT_AND_RETRY`（注入提示后重试）、`GIVE_UP`（放弃，返回错误给上层）。恢复动作的执行需要回调 `Agent` 的能力（压缩、注入提示），所以 `LlmErrorRecovery` 要么作为 `Agent` 的内部组件，要么通过接口注入所需能力。

### 4.2 与 chat 调用和 Agent 循环的衔接

衔接分两层。第一层在 `AbstractOpenAiCompatibleClient.chat` 内部，把原始异常或非成功 HTTP 响应转换成 `LlmError` 抛出，而不是抛裸 `IOException`。这一层只做分类，不做恢复，保持 client 的纯粹性。第二层在 `Agent.java` 主循环，catch `LlmError` 后调用 `LlmErrorRecovery.recover`，根据返回的 `RecoveryAction` 决定下一步。

`COMPACT_AND_RETRY` 是最有价值的一个动作，它把 `ConversationHistoryCompactor` 和错误处理打通。具体流程：捕获到 `PROMPT_TOO_LONG` → 调用 `ConversationHistoryCompactor.compact` 压缩当前 history → 用压缩后的 history 重试 `chat`。如果重试还报 `PROMPT_TOO_LONG`，再压缩一轮（压缩更激进），最多压缩 N 轮后仍失败才放弃。这让 PAICLI 的上下文管理从"预估阈值主动压缩"升级为"预估 + 反馈双保险"，预估漏了的时候反馈兜底。

`HINT_AND_RETRY` 针对 `EMPTY_RESPONSE` 和 `MAX_OUTPUT`。`EMPTY_RESPONSE` 时往 history 注入一条"请继续输出"的系统提示再重试；`MAX_OUTPUT` 时注入"请从上次中断处继续，不要重复已输出内容"再重试，并临时调大 max_tokens。这两类重试要限制次数，避免模型反复输出空或反复截断。

`RETRY` 针对 `RATE_LIMITED` 和 `NETWORK_ERROR`，指数退避（比如 1s、2s、4s），最多重试 3 次。`GIVE_UP` 针对 `AUTH_ERROR` 和持续 `PROVIDER_ERROR`，直接把结构化错误返回给用户，提示该修什么（API key、额度、provider 状态）。

### 4.3 Provider 适配的归位

PAICLI 现在各 provider client（`DeepSeekClient`、`KimiClient`、`GLMClient` 等）里散落着 provider 特殊处理。引入 `LlmErrorClassifier` 后，provider 特有的错误分类规则应该集中到分类器的 provider 策略里，而不是散在各 client。比如 DeepSeek 的特定错误码、Kimi 的温度策略错误、讯飞 MaaS 的鉴权错误格式，都注册成 `LlmErrorClassifier` 的 provider 插件。

这其实是把 PAICLI 现有的"请求构造层 provider 适配"延伸到"错误处理层 provider 适配"，让 provider 适配在两个层面都成体系，而不是只在请求构造时感知 provider 差异、错误处理时又退回通用逻辑。`LlmClientFactory` 在创建 client 时，一并注册该 provider 的错误分类策略，保持工厂的统一出口。

### 4.4 与现有 compaction 阈值的协同

PAICLI 现在的 `ConversationHistoryCompactor` 按 token 估算阈值主动压缩（大窗口 `window - 20k - 13k`）。引入响应式压缩后，主动压缩和响应式压缩要协同，不能打架。建议保留主动压缩作为第一道防线（避免大多数情况下走到 LLM 报错），响应式压缩作为第二道兜底（主动压缩估算漏了时补救）。响应式压缩触发时，可以适当调低主动压缩的阈值，让后续轮次更早主动压缩，减少对响应式的依赖。

这种"主动 + 响应"双保险的好处是，即使 token 估算不准（不同 provider 的 tokenizer 不同，估算误差难免），也不会因为估算漏了而让任务卡在超长错误上。响应式压缩基于 provider 实际反馈，是 ground truth，比任何估算都可靠。

## 五、迭代步骤

第一步，定义 `LlmError` 和 `LlmErrorCategory` 枚举，把现有 `IOException` 在 client 层包装成 `LlmError`，但恢复逻辑暂时不变（仍然往上抛）。这一步只做结构化，让上层能拿到类别，是纯重构，行为不变。

第二步，实现 `LlmErrorClassifier` 的通用分类规则（基于 HTTP 状态码和通用关键词），覆盖最常见的几类。每个 provider 的特有规则先留空，用通用规则兜底。这一步让分类能力可用，但还没接入恢复。

第三步，实现 `LlmErrorRecovery` 的 `RETRY`（指数退避）和 `GIVE_UP` 两类，接进 `Agent.java`。这两类最简单、风险最低，先让网络瞬断和鉴权失败有正确处理。验证重试确实能救回瞬断。

第四步，实现 `COMPACT_AND_RETRY`，打通 `ConversationHistoryCompactor`。这是价值最大的一步，也是复杂度最高的一步，单独迭代，重点测试压缩后重试的成功率和压缩激进度的平衡。

第五步，实现 `HINT_AND_RETRY`，处理空响应和输出超限。

第六步，补全各 provider 的特有分类规则，把散在各 client 的错误处理逻辑归位到分类器策略。

文档同步：第一步起在 `docs/` 新建 `phase-27-llm-error-recovery.md`，第四步更新 `AGENTS.md` 的 Memory 段（响应式压缩说明），第六步更新各 provider 文档。

## 六、测试方案

第一层，`LlmErrorClassifier` 单元测试。对每个类别构造代表性的原始异常/响应（422 + 各种关键词、429、401、空响应体、finish_reason=length），验证分类正确。重点测试 provider 特有规则和通用规则的优先级。

第二层，`LlmErrorRecovery` 单元测试。mock `Agent` 的压缩和注入能力，验证每类错误返回正确的 `RecoveryAction`：`PROMPT_TOO_LONG` → `COMPACT_AND_RETRY`、`NETWORK_ERROR` → `RETRY` 带退避、`AUTH_ERROR` → `GIVE_UP`。验证退避次数和间隔、压缩重试次数上限。

第三层，client 集成测试。mock HTTP 层返回各种错误响应，验证 client 抛出的是带正确类别的 `LlmError` 而非裸 `IOException`。

第四层，`Agent` 循环集成测试。mock `LlmClient` 在特定调用次数后抛特定错误，验证循环的恢复行为：第一次抛 `PROMPT_TOO_LONG` → 触发压缩 → 重试成功；连续抛 `NETWORK_ERROR` → 退避重试 → 第三次成功；抛 `AUTH_ERROR` → 不重试直接报错。验证不死循环（重试有上限）。

第五层，端到端手测。故意构造超长上下文（让模型读大量文件）触发 `PROMPT_TOO_LONG`，观察响应式压缩是否救回；断网触发 `NETWORK_ERROR`，观察重试是否成功；用失效 key 触发 `AUTH_ERROR`，观察是否给出明确提示而非反复重试。

## 七、风险与权衡

第一个权衡是响应式压缩的激进程度。压缩太轻，压缩后还超长，反复压缩浪费时间；压缩太重，一次压缩就把关键上下文丢了，任务质量下降。建议压缩激进度随重试次数递增——第一次响应式压缩用温和策略（保留最近 2 个 user 轮次），第二次用激进策略（保留最近 1 个轮次），给模型一个渐进的上下文收缩，而不是一上来就砍到底。

第二个权衡是重试的副作用。重试会消耗额外 token 和时间，尤其 `COMPACT_AND_RETRY` 每次都压缩 + 重试，开销不小。建议重试次数有硬上限（比如每类最多 3 次），且在重试时通过 inline renderer 给用户可见的进度提示（"上下文超长，正在压缩重试..."），避免用户以为 Agent 卡死。PAICLI 的 inline renderer 本来就支持活动状态展示，正好复用。

第三个权衡是错误分类的准确性。provider 错误信息格式各异，分类器可能误判。比如某个 provider 把限流错误返回成 500 而非 429，分类器会误判成 `PROVIDER_ERROR` 而非 `RATE_LIMITED`，导致不重试。缓解办法是分类器除了状态码，还要解析响应体关键词（"rate limit"、"quota"、"timeout"），多信号交叉判断；同时分类器要可配置、可扩展，用户发现误判时能加规则修正。

第四个权衡是和 streaming 的交互。PAICLI 的 inline renderer 在 LLM 调用期间有 live thinking 区显示 reasoning。如果调用中途出错（比如流到一半网络断），恢复逻辑要能处理"部分流已显示"的情况——重试时不能把已经显示的 reasoning 重复显示，要么清掉 live 区重来，要么从断点续传。这增加了恢复逻辑的复杂度，建议第一版只处理"调用前就失败"的错误，流中途失败的恢复留到后续迭代。

第五个权衡是是否对 Plan-and-Execute 和 Multi-Agent 路径都接入。这两条路径也调 `LlmClient`，但它们的循环结构和 ReAct 不同。建议 `LlmErrorRecovery` 作为可复用组件，三条路径都接入，但接入点各自适配——ReAct 在主循环 catch，Plan-and-Execute 在每个 Task 执行处 catch，Multi-Agent 在子 Agent 调度处 catch。共享分类和恢复逻辑，避免三套重复实现。

## 八、小结

模型错误分类与恢复要解决的，是 PAICLI 在 LLM 错误处理上"一刀切、无判断力"的短板。当前所有错误被当成同质的失败，框架不区分错误性质，导致该重试的不重试、该压缩的不压缩、该放弃的还在傻重试，Agent 在错误面前既不聪明也不健壮。

MyCodeAgent 的解法把错误显式分类，每类配针对性恢复策略，其中最有价值的是响应式上下文压缩——把上下文管理从纯预估式升级为"预估 + 反馈"双保险。这一点对 PAICLI 尤其有意义，因为 PAICLI 已经有 `ConversationHistoryCompactor` 这块基础设施，只差把它和错误处理打通，投入产出比很高。

落地建议从风险最低的 `RETRY` 和 `GIVE_UP` 起步，再啃最有价值的 `COMPACT_AND_RETRY`，最后补全 provider 适配。这套设计的本质，是让 PAICLI 的错误处理从"被动兜底"升级为"主动恢复"，让 Agent 在 LLM 不可避免的各类错误面前，能像有经验的工程师一样判断该重试、该压缩、还是该认输报错，而不是无脑重试或直接放弃。这种判断力，是 Agent 从"能用"走向"可靠"的关键一环。






这其实是把"框架该做的重试"错误地外包给了模型。

