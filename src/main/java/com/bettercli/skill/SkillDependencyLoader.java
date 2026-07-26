package com.bettercli.skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析 {@code skill-dependencies} 的加载顺序：依赖在前、主 skill 在后；检测环与缺失。
 */
public final class SkillDependencyLoader {

    public static final int MAX_DEPTH = 8;

    public record Resolution(
            List<Skill> loadOrder,
            List<String> missing,
            List<String> cycles
    ) {
    }

    private SkillDependencyLoader() {
    }

    public static Resolution resolve(Skill root, SkillRegistry registry) {
        List<Skill> order = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> cycles = new ArrayList<>();
        if (root == null || registry == null) {
            return new Resolution(List.of(), missing, cycles);
        }
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        walk(root.name(), root, registry, visiting, visited, order, missing, cycles, 0);
        return new Resolution(List.copyOf(order), List.copyOf(missing), List.copyOf(cycles));
    }

    private static void walk(String requestedName,
                             Skill skill,
                             SkillRegistry registry,
                             Set<String> visiting,
                             Set<String> visited,
                             List<Skill> order,
                             List<String> missing,
                             List<String> cycles,
                             int depth) {
        if (skill == null) {
            missing.add(requestedName);
            return;
        }
        String name = skill.name();
        if (visited.contains(name)) {
            return;
        }
        if (visiting.contains(name)) {
            cycles.add(name);
            return;
        }
        if (depth > MAX_DEPTH) {
            cycles.add(name + "(depth>" + MAX_DEPTH + ")");
            return;
        }
        visiting.add(name);
        for (String depName : skill.dependencies()) {
            if (depName == null || depName.isBlank()) {
                continue;
            }
            Skill dep = registry.findSkill(depName.trim());
            walk(depName.trim(), dep, registry, visiting, visited, order, missing, cycles, depth + 1);
        }
        visiting.remove(name);
        visited.add(name);
        order.add(skill);
    }
}
