package com.bettercli.plan;

import java.util.List;

/**
 * 计划 DAG 校验结果。LLM 生成的计划 JSON 不可靠，{@link Planner} 解析后用它定位问题、
 * 自动修复可恢复项（悬空依赖 / 自依赖）、并对不可恢复项（环）给出可定位的诊断。
 *
 * @param valid               是否通过校验（无悬空依赖、无自依赖、无环）
 * @param danglingDependencies 指向不存在任务的依赖，形如 "task_2->task_99"
 * @param selfDependencies     自依赖的任务 id
 * @param cycle                构成环的任务 id 路径（按依赖方向），空表示无环
 */
public record PlanValidationResult(boolean valid, List<String> danglingDependencies,
                                    List<String> selfDependencies, List<String> cycle) {

    public static PlanValidationResult ok() {
        return new PlanValidationResult(true, List.of(), List.of(), List.of());
    }
}
