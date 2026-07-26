package com.bettercli.subagent;

import com.bettercli.skill.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Custom SubAgent 加载与运行时维护。
 *
 * <p>三层目录扫描顺序（后者整体覆盖前者同名定义）：
 * <ol>
 *   <li>builtin（可选，jar 解压或测试注入）</li>
 *   <li>user：{@code ~/.bettercli/agents/<name>/AGENT.md}</li>
 *   <li>project：{@code <project>/.bettercli/agents/<name>/AGENT.md}</li>
 * </ol>
 *
 * <p>支持 frontmatter {@code from} / {@code extends}：在全部层加载后按名字继承本地基座
 * （CLI 侧对标文档 {@code platform} 复用，不接 RoleHub）。
 */
public final class CustomSubAgentRegistry {

    private final Path builtinAgentsDir;
    private final Path userAgentsDir;
    private final Path projectAgentsDir;

    private final Map<String, CustomSubAgentDefinition> byName = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public CustomSubAgentRegistry(Path builtinAgentsDir, Path userAgentsDir, Path projectAgentsDir) {
        this.builtinAgentsDir = builtinAgentsDir;
        this.userAgentsDir = userAgentsDir;
        this.projectAgentsDir = projectAgentsDir;
    }

    public synchronized void reload() {
        byName.clear();
        warnings.clear();
        loadDirectory(builtinAgentsDir, CustomSubAgentDefinition.Source.BUILTIN);
        loadDirectory(userAgentsDir, CustomSubAgentDefinition.Source.USER);
        loadDirectory(projectAgentsDir, CustomSubAgentDefinition.Source.PROJECT);
        resolveInheritance();
    }

    public synchronized List<CustomSubAgentDefinition> all() {
        return byName.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    public synchronized CustomSubAgentDefinition find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return byName.get(name.trim());
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 解析 {@code from}/{@code extends}，检测环与缺失基座。 */
    private void resolveInheritance() {
        List<String> names = new ArrayList<>(byName.keySet());
        for (String name : names) {
            CustomSubAgentDefinition def = byName.get(name);
            if (def == null || def.extendsFrom() == null || def.extendsFrom().isBlank()) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            seen.add(name);
            CustomSubAgentDefinition merged = def;
            String cursor = def.extendsFrom().trim();
            int depth = 0;
            while (cursor != null && !cursor.isBlank() && depth < 8) {
                if (!seen.add(cursor)) {
                    String msg = "Custom SubAgent [" + name + "] from 存在循环: " + cursor;
                    warnings.add(msg);
                    System.err.println("⚠️ " + msg);
                    break;
                }
                CustomSubAgentDefinition base = byName.get(cursor);
                if (base == null) {
                    String msg = "Custom SubAgent [" + name + "] from=\"" + cursor + "\" 未找到基座";
                    warnings.add(msg);
                    System.err.println("⚠️ " + msg);
                    break;
                }
                merged = merged.mergeOver(base);
                cursor = base.extendsFrom();
                depth++;
            }
            byName.put(name, merged);
        }
    }

    private void loadDirectory(Path dir, CustomSubAgentDefinition.Source source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path entry : entries) {
                Path agentMd = entry.resolve("AGENT.md");
                if (!Files.isRegularFile(agentMd)) {
                    continue;
                }
                CustomSubAgentDefinition def = parseAgent(entry, agentMd, source);
                if (def != null) {
                    byName.put(def.name(), def);
                }
            }
        } catch (IOException e) {
            String msg = "扫描 Custom SubAgent 目录失败 " + dir + ": " + e.getMessage();
            warnings.add(msg);
            System.err.println("⚠️ " + msg);
        }
    }

    private CustomSubAgentDefinition parseAgent(Path agentDir, Path agentMd,
                                                 CustomSubAgentDefinition.Source source) {
        String content;
        try {
            content = Files.readString(agentMd);
        } catch (IOException e) {
            String msg = "读取 AGENT.md 失败 " + agentMd + ": " + e.getMessage();
            warnings.add(msg);
            System.err.println("⚠️ " + msg);
            return null;
        }

        SkillFrontmatterParser.ParseResult parsed = SkillFrontmatterParser.parse(content);
        for (String w : parsed.warnings()) {
            warnings.add(agentMd + ": " + w);
            System.err.println("⚠️ Custom SubAgent " + agentMd + " frontmatter: " + w);
        }

        Map<String, Object> fm = parsed.frontmatter();
        String name = stringField(fm, "name");
        if (name == null || name.isBlank()) {
            name = agentDir.getFileName().toString();
        }
        String description = stringField(fm, "description");
        if (description == null) {
            description = "";
        }
        String model = stringField(fm, "model");
        Integer maxTurns = intField(fm, "maxTurns");
        Integer timeoutSeconds = intField(fm, "timeoutSeconds");
        List<String> allowedTools = listField(fm, "allowedTools");
        List<String> disallowedTools = listField(fm, "disallowedTools");
        List<String> skills = listField(fm, "skills");
        String extendsFrom = stringField(fm, "from");
        if (extendsFrom == null || extendsFrom.isBlank()) {
            extendsFrom = stringField(fm, "extends");
        }

        String memoryMd = readOptionalSidecar(agentDir, "MEMORY.md");
        String soulMd = readOptionalSidecar(agentDir, "SOUL.md");
        String identityMd = readOptionalSidecar(agentDir, "IDENTITY.md");

        return new CustomSubAgentDefinition(
                name,
                description,
                parsed.body(),
                model,
                maxTurns,
                timeoutSeconds,
                allowedTools,
                disallowedTools,
                skills,
                memoryMd,
                soulMd,
                identityMd,
                source,
                agentMd,
                extendsFrom
        );
    }

    private String readOptionalSidecar(Path agentDir, String fileName) {
        Path path = agentDir.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            warnings.add("读取 " + fileName + " 失败 " + path + ": " + e.getMessage());
            return "";
        }
    }

    private static String stringField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v instanceof String s ? s : null;
    }

    private static Integer intField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> listField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(x -> x instanceof String)
                    .map(x -> (String) x)
                    .toList();
        }
        return Collections.emptyList();
    }
}
