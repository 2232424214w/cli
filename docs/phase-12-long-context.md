# 第 12 期开发任务：长上下文工程

> 本期目标是让 BetterCLI 的运行策略随模型上下文窗口变化。后续主轨压缩已进一步对齐 1024 Context Checkpoint Compaction（见 `feat-context-compaction` / `AGENTS.md`）。

## 1. 已交付范围

- `LlmClient` 能力声明：
  - `maxContextWindow()`
  - `supportsPromptCaching()`
  - `promptCacheMode()`
- 模型默认能力：
  - GLM-5.1：`200000` window，`glm-prompt-cache`
  - DeepSeek V4：`1000000` window，`automatic-prefix-cache`
- `ContextProfile`：参数由 `maxContextWindow` 统一推导（不再分 short / balanced / long 档）
  - 可用压缩上限 = `window − 20k buffer − 8k maxOut`
  - 消息体阈值 20k；短期记忆预算 = `window × 0.45`
  - MCP resource 索引：`window ≥ 32k`
- `AgentBudget` 动态预算：默认 `80% * maxContextWindow`
- 主轨 `ConversationHistoryCompactor`：Pre-Turn / Mid-Turn / API 兜底 + session.jsonl 检查点（任务种子钉住 + 摘要分节 + 按轮次渐进裁剪）
- Resume 回填 `session_search`；jsonl rotate 只丢检查点前 raw 行
- 辅轨 `ContextCompressor`：短期记忆 Map-Reduce（不替代主轨）
- 代码检索：`search_code` topK 按窗口自适应；精确定位优先 `glob` / `grep` / `read_file`
- Token 可见化与 `/context` 双条件展示

## 2. 明确不做

- 不新增 Anthropic / Claude provider
- 不实现 Anthropic `cache_control` 块
- 不把 MCP resource body 自动塞进 system prompt

## 3. 核心文件

```text
src/main/java/com/bettercli/context/ContextProfile.java
src/main/java/com/bettercli/memory/ConversationHistoryCompactor.java
src/main/java/com/bettercli/memory/CompactConfig.java
src/main/java/com/bettercli/memory/SessionCheckpointStore.java
```

最终验证：`mvn test`
