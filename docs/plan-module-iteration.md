# Plan 模块迭代：DAG 校验 + 解析健壮性 + 可诊断

> 本文记录 Plan 模块（`Planner` / `ExecutionPlan` / `Task`）一次完整迭代的前后变化。
> 迭代动机：LLM 生成的计划 JSON 不可靠，原实现对错误 DAG 只给"存在循环依赖"这种不可定位的废信息，
> 且对可恢复问题（悬空依赖、自依赖）要么静默丢边要么直接判死。迭代目标：把"不可诊断"升级为"可定位 + 可修复"。

## 1. 迭代前的问题（代码实证）

### 1.1 环报错不可定位
`Planner.parsePlan` 命中环时：
```java
if (!plan.computeExecutionOrder()) {
    throw new IOException("计划中存在循环依赖");  // 哪些任务成环？不知道
}
```
`ExecutionPlan.computeExecutionOrder()` 只返回 `boolean`，环路径信息在 `topologicalSort` 里被丢弃。

### 1.2 悬空依赖静默丢边
`Planner.parsePlan` 第二遍建依赖：
```java
Task dep = plan.getTask(newDepId);
if (dep != null) {        // 依赖指向不存在的任务时 dep==null
    task.addDependency(...);  // 静默跳过，无告警
}
```
LLM 把 `task_2` 的依赖写成 `task_99`（typo）时，这条边被无声丢弃，计划结构被悄悄改变，用户和上游都不知情。

### 1.3 自依赖被当成不可恢复的环
LLM 偶尔会输出 `task_1` 依赖 `task_1`。原实现：`dep = plan.getTask("task_1")` 非空 → `addDependency("task_1")` → `computeExecutionOrder` 的 `topologicalSort` 命中 `visiting` → 返回 false → 抛"存在循环依赖"。但自依赖是平凡可修复的（丢掉即可），不该判死整个计划。

### 1.4 `inferSimpleTaskType` 运算符优先级 bug
```java
if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")
        && normalized.contains("文件")) {
    return Task.TaskType.FILE_READ;
}
```
Java 里 `&&` 优先级高于 `||`，实际语义是 `读取 || 打开 || (查看 && 文件)`。导致"读取X"判 FILE_READ、"查看X"（不含"文件"）落到 COMMAND，三个同义动词行为不一致。

### 1.5 `replan` 把失败上下文当成新目标
```java
public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) {
    ...
    context.append("原任务: ").append(failedPlan.getGoal())...
    return createPlan(context.toString());  // context 成了新 goal
}
```
重规划后的 `plan.getGoal()` 是一长串"原任务: X\n失败原因: Y\n已完成..."，不是用户原始目标，污染后续展示与记忆。

### 1.6 计划停滞时不可诊断
`PlanExecuteAgent.executePlan` 停滞分支：
```java
if (!plan.isAllCompleted() && !plan.hasFailed()) {
    return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";  // 哪些任务被卡？不知道
}
```

## 2. 迭代后的变化

### 2.1 新增 `PlanValidationResult` + `ExecutionPlan.validate()`
新 record 承载四类问题：
```java
public record PlanValidationResult(boolean valid, List<String> danglingDependencies,
                                    List<String> selfDependencies, List<String> cycle) {}
```
`ExecutionPlan.validate()` 一次性扫描：悬空依赖（`task_2 -> task_99`）、自依赖（`task_3`）、环（路径）。

### 2.2 新增 `ExecutionPlan.detectCycle()` 返回环路径
基于 DFS + 递归栈路径，命中回边时返回从首次出现位置到栈顶的路径：
```java
public List<String> detectCycle() { ... }
// 例：[task_1, task_2, task_3] 表示 task_1 -> task_2 -> task_3 -> task_1
```
关键设计：`detectCycle` 跳过自环（`depId.equals(id)`）和悬空边（`!tasks.containsKey(depId)`），让自依赖归 `selfDependencies`、悬空归 `danglingDependencies`、`cycle` 只报真正的多节点环——三类问题正交，不互相误判。

### 2.3 `Planner.parsePlan` 解析期自动修复 + 环诊断
```java
if (newDepId.equals(newId)) {
    log.warn("Plan task {} self-depends on {}, dropping", newId, newDepId);
    continue;                       // 自依赖：丢弃，可恢复
}
Task dep = plan.getTask(newDepId);
if (dep == null) {
    log.warn("... dropping dangling dependency", ...);
    continue;                       // 悬空依赖：丢弃并告警，不再静默
}
...
if (!plan.computeExecutionOrder()) {
    List<String> cycle = plan.detectCycle();
    throw new IOException("计划中存在循环依赖: " + String.join(" -> ", cycle));  // 带路径
}
```
- 自依赖、悬空依赖：从源头不加入，计划可继续执行（可恢复）。
- 真环：抛带路径的异常（不可恢复但可定位）。

### 2.4 修复 `inferSimpleTaskType` 优先级
```java
if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")) {
    return Task.TaskType.FILE_READ;   // 三个读取类动词一致
}
```
"查看日志"现在正确判为 FILE_READ（迭代前是 COMMAND）。

### 2.5 `replan` 保留原目标
新增 `createPlan(String goal, String extraContext)` 重载，`replan` 调用它：
```java
return createPlan(failedPlan.getGoal(), context.toString());
// 重规划后 plan.getGoal() == 原始目标；失败上下文作为 user message 的补充段
```

### 2.6 `PlanExecuteAgent` 停滞诊断列出被卡任务
```java
List<String> blocked = plan.getAllTasks().stream()
        .filter(t -> t.getStatus() == Task.TaskStatus.PENDING)
        .map(Task::getId).toList();
return "⚠️ 计划未能继续推进，存在未满足依赖的任务: " + String.join(", ", blocked);
```

## 3. 前后对照表

| 维度 | 迭代前 | 迭代后 |
|------|--------|--------|
| 环报错 | "存在循环依赖"（无定位） | "存在循环依赖: task_1 -> task_2 -> task_1" |
| 悬空依赖 | 静默丢边，无告警 | 丢弃 + `log.warn` 告警 |
| 自依赖 | 判死整个计划（当环抛错） | 丢弃 + 告警，计划继续 |
| DAG 校验 API | 无 | `validate()` 返回三类问题；`detectCycle()` 返回环路径 |
| `inferSimpleTaskType` | `查看X` 误判 COMMAND（优先级 bug） | 三个读取类动词一致判 FILE_READ |
| `replan` 目标 | 失败上下文当新目标 | 保留原目标，失败上下文作补充 |
| 计划停滞报错 | "存在未满足依赖的任务。" | 列出被卡住的 PENDING 任务 id |

## 4. 测试覆盖

`ExecutionPlanTest`（+3）：`detectCycleReturnsEmptyForAcyclicPlan`、`detectCycleReturnsPathForCircularDependency`、`validateFlagsDanglingAndSelfDependencies`。
`PlannerTest`（+4）：`parsePlanAutoRepairsSelfAndDanglingDependencies`、`parsePlanThrowsWithCyclePathInsteadOfGenericMessage`、`inferSimpleTaskTypeTreatsViewVerbAsFileRead`、`replanPreservesOriginalGoalInsteadOfUsingFailureContextAsGoal`。

回归：`mvn -Dtest=ExecutionPlanTest,PlannerTest,PlanExecuteAgentTest` → 20/20 绿。

## 5. 设计要点（面试可说）

- **正交分类**：悬空依赖、自依赖、环是三类独立问题，`detectCycle` 主动跳过自环和悬空边，避免一类问题被另一类误报，让 `validate()` 的三个字段语义干净。
- **可恢复 vs 不可恢复**：自依赖/悬空依赖是 LLM 输出噪声，丢弃即可恢复执行；真环是结构错误，不可恢复但必须可定位。两类走不同处理路径（丢弃+告警 vs 带路径抛错）。
- **从源头修复**：自依赖/悬空依赖在 `parsePlan` 第二遍建边时就跳过，而不是先加入再清理——避免 `addTask` 的 `dependents` 反向指针和 `dependencies` 正向指针不一致。
- **诊断优于沉默**：原实现对悬空依赖静默丢边是最危险的——计划结构被悄悄改变还继续执行。迭代后即使丢弃也告警，让问题可观测。
