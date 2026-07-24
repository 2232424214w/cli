# Agent Eval（最小闭环）

独立于 CLI 主流程的 Agent 任务级评测 harness。

## 文件

- `golden-tasks.jsonl`：黄金任务（id / mode / input / success）
- Java 实现：`com.bettercli.eval.*`（Runner / 确定性判分 / Report）

## 跑测试

```bash
mvn test -Dtest=DeterministicScorerTest,EvalRunnerTest -DskipTests=false
```

当前最小闭环用脚本化 `RecordingClient` 验证 harness 本身；接入真实 LLM 时把 `EvalRunner` 的 `LlmFactory` 换成生产 client 即可。
