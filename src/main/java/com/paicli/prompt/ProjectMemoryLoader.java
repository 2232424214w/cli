package com.paicli.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads PaiCLI project memory files that are intended to be versioned and
 * injected into the system prompt at session start.
 *
 * 增强版本（对标美团 1024 Agent MEMORY.md + Claude Code CLAUDE.md）：
 * 1. 保留原有 5 个位置的加载（用户级 + 项目级 + 本地覆盖）
 * 2. 新增向上递归查找（对标 Claude Code，从工作目录向上找 PAI.md 拼接）
 * 3. 新增 PAI.md 主体容量管理（默认 2200 字符，超 80% 提示整合）
 * 4. 新增容量管理 API（供 read_pai_md 工具和 suggest_pai_md 工具使用）
 */
public class ProjectMemoryLoader {
    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryLoader.class);

    /** 总字符预算（含 @relative/path.md 导入内容），保留原有行为 */
    private static final int MAX_TOTAL_CHARS = 24_000;
    private static final int MAX_IMPORT_DEPTH = 3;

    /** PAI.md 主体字符上限（对标美团 2200 字符），可通过系统属性覆盖 */
    private static final int DEFAULT_PAI_MD_MAX_CHARS = 2_200;
    /** 容量整合阈值（超此比例提示 Agent 主动整合） */
    private static final double DEFAULT_INTEGRATE_THRESHOLD = 0.8;

    private final Path userConfigDir;
    private final Path projectRoot;
    private final int paiMdMaxChars;
    private final double integrateThreshold;
    private final boolean recursiveDiscovery;

    public ProjectMemoryLoader(Path userConfigDir, Path projectRoot) {
        this(userConfigDir, projectRoot, false);
    }

    public ProjectMemoryLoader(Path userConfigDir, Path projectRoot, boolean recursiveDiscovery) {
        this(userConfigDir, projectRoot, recursiveDiscovery,
                resolvePaiMdMaxChars(), DEFAULT_INTEGRATE_THRESHOLD);
    }

    public ProjectMemoryLoader(Path userConfigDir, Path projectRoot, boolean recursiveDiscovery,
                               int paiMdMaxChars, double integrateThreshold) {
        this.userConfigDir = userConfigDir == null ? null : userConfigDir.toAbsolutePath().normalize();
        this.projectRoot = projectRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
        this.recursiveDiscovery = recursiveDiscovery;
        this.paiMdMaxChars = paiMdMaxChars;
        this.integrateThreshold = integrateThreshold;
    }

    public static ProjectMemoryLoader createDefault(Path projectRoot) {
        return new ProjectMemoryLoader(
                Path.of(System.getProperty("user.home"), ".paicli"),
                projectRoot,
                isRecursiveDiscoveryEnabled());
    }

    private static int resolvePaiMdMaxChars() {
        String configured = System.getProperty("paicli.pai_md.max_chars");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(100, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException ignored) {
                log.warn("Invalid paicli.pai_md.max_chars value: {}, using default {}", configured, DEFAULT_PAI_MD_MAX_CHARS);
            }
        }
        String env = System.getenv("PAICLI_PAI_MD_MAX_CHARS");
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(100, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) {
                log.warn("Invalid PAICLI_PAI_MD_MAX_CHARS value: {}, using default {}", env, DEFAULT_PAI_MD_MAX_CHARS);
            }
        }
        return DEFAULT_PAI_MD_MAX_CHARS;
    }

    private static boolean isRecursiveDiscoveryEnabled() {
        String prop = System.getProperty("paicli.pai_md.recursive_discovery");
        if (prop != null && !prop.isBlank()) {
            return Boolean.parseBoolean(prop.trim());
        }
        String env = System.getenv("PAICLI_PAI_MD_RECURSIVE_DISCOVERY");
        return env != null && Boolean.parseBoolean(env.trim());
    }

    public String loadForPrompt() {
        List<MemorySource> sources = sources();
        StringBuilder body = new StringBuilder();
        Set<Path> importStack = new HashSet<>();

        for (MemorySource source : sources) {
            if (!Files.isRegularFile(source.path())) {
                continue;
            }
            String content = readWithImports(source.path(), source.importRoot(), importStack, 0).trim();
            if (content.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append("### ").append(label(source.path())).append("\n\n").append(content);
            if (body.length() >= MAX_TOTAL_CHARS) {
                return truncateSection(body);
            }
        }

        if (body.isEmpty()) {
            return "";
        }
        return "## PAI.md 项目记忆\n\n" + body;
    }

    private List<MemorySource> sources() {
        List<MemorySource> sources = new ArrayList<>();
        if (userConfigDir != null) {
            sources.add(new MemorySource(userConfigDir.resolve("PAI.md"), userConfigDir));
        }
        // 向上递归查找（对标 Claude Code）：从项目根向上查找 PAI.md
        if (recursiveDiscovery) {
            sources.addAll(discoverRecursivePaiMdSources());
        }
        // 原有项目级 + 本地覆盖加载（保留向后兼容）
        sources.add(new MemorySource(projectRoot.resolve("PAI.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve(".paicli").resolve("PAI.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve("PAI.local.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve(".paicli").resolve("PAI.local.md"), projectRoot));
        return deduplicateSources(sources);
    }

    /**
     * 向上递归查找 PAI.md（从项目根向文件系统根方向）。
     * 对标 Claude Code 的"walking up the directory tree"机制。
     * 跳过项目根本身（避免与后续项目级加载重复），只查找祖先目录。
     */
    private List<MemorySource> discoverRecursivePaiMdSources() {
        List<MemorySource> discovered = new ArrayList<>();
        Path current = projectRoot.getParent();
        Path fileSystemRoot = projectRoot.getRoot();
        int maxDepth = 20;  // 防止过深递归
        int depth = 0;
        while (current != null && depth < maxDepth) {
            Path paiMd = current.resolve("PAI.md");
            if (Files.isRegularFile(paiMd)) {
                // 用当前目录作为 importRoot，允许 @relative 导入
                discovered.add(new MemorySource(paiMd, current));
            }
            if (current.equals(fileSystemRoot)) {
                break;
            }
            current = current.getParent();
            depth++;
        }
        // 顺序：靠近项目根的在前，靠近文件系统根的在后
        // 这样拼接时越靠近工作目录的越后读（覆盖优先级越高）
        // 但 Claude Code 的顺序是"从根到工作目录"，我们也按这个顺序
        java.util.Collections.reverse(discovered);
        return discovered;
    }

    private List<MemorySource> deduplicateSources(List<MemorySource> sources) {
        List<MemorySource> unique = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (MemorySource source : sources) {
            if (seen.add(source.path())) {
                unique.add(source);
            }
        }
        return unique;
    }

    private String readWithImports(Path file, Path importRoot, Set<Path> importStack, int depth) {
        Path normalized = file.toAbsolutePath().normalize();
        if (depth > MAX_IMPORT_DEPTH) {
            log.warn("Skipping PAI.md import beyond depth {}: {}", MAX_IMPORT_DEPTH, normalized);
            return "";
        }
        if (!normalized.startsWith(importRoot) || !Files.isRegularFile(normalized)) {
            log.warn("Skipping PAI.md import outside allowed root or missing file: {}", normalized);
            return "";
        }
        if (!importStack.add(normalized)) {
            log.warn("Skipping cyclic PAI.md import: {}", normalized);
            return "";
        }

        try {
            StringBuilder out = new StringBuilder();
            for (String line : Files.readAllLines(normalized, StandardCharsets.UTF_8)) {
                String importPath = parseImport(line);
                if (importPath == null) {
                    out.append(line).append("\n");
                    continue;
                }
                Path imported = normalized.getParent().resolve(importPath).normalize();
                String importedContent = readWithImports(imported, importRoot, importStack, depth + 1).trim();
                if (!importedContent.isBlank()) {
                    out.append(importedContent).append("\n");
                }
            }
            return out.toString();
        } catch (IOException e) {
            log.warn("Failed to read PAI.md memory file: {}", normalized, e);
            return "";
        } finally {
            importStack.remove(normalized);
        }
    }

    private static String parseImport(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("@") || trimmed.length() < 2 || trimmed.contains(" ")) {
            return null;
        }
        String path = trimmed.substring(1).trim();
        if (path.startsWith("/") || path.contains("..")) {
            return null;
        }
        return path;
    }

    private static String truncateSection(StringBuilder body) {
        int keep = Math.max(0, MAX_TOTAL_CHARS - 80);
        String truncated = body.substring(0, Math.min(body.length(), keep)).stripTrailing();
        return "## PAI.md 项目记忆\n\n" + truncated + "\n\n[PAI.md 内容已按 " + MAX_TOTAL_CHARS + " 字符预算截断]";
    }

    private static String label(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    // ==================== 容量管理 API（供 read_pai_md / suggest_pai_md 工具使用） ====================

    /**
     * 读取 PAI.md 完整内容（不含导入展开，只读主体文件）。
     * 供 read_pai_md 工具调用。
     */
    public String readContent() {
        StringBuilder body = new StringBuilder();
        Set<Path> importStack = new HashSet<>();
        for (MemorySource source : sources()) {
            if (!Files.isRegularFile(source.path())) {
                continue;
            }
            String content = readWithImports(source.path(), source.importRoot(), importStack, 0).trim();
            if (content.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append("### ").append(label(source.path())).append("\n\n").append(content);
        }
        return body.toString();
    }

    /**
     * 获取当前 PAI.md 主体的字符数（不含导入展开）。
     */
    public int getCharCount() {
        int total = 0;
        for (MemorySource source : sources()) {
            if (!Files.isRegularFile(source.path())) {
                continue;
            }
            try {
                String content = Files.readString(source.path(), StandardCharsets.UTF_8);
                total += content.length();
            } catch (IOException ignored) {
                // 忽略无法读取的文件
            }
        }
        return total;
    }

    /**
     * 获取 PAI.md 主体字符上限。
     */
    public int getMaxChars() {
        return paiMdMaxChars;
    }

    /**
     * 获取容量整合阈值（0-1）。
     */
    public double getIntegrateThreshold() {
        return integrateThreshold;
    }

    /**
     * 判断当前 PAI.md 是否超过整合阈值。
     */
    public boolean isOverThreshold() {
        return getCharCount() >= (int) (paiMdMaxChars * integrateThreshold);
    }

    /**
     * 判断当前 PAI.md 是否超过字符上限。
     */
    public boolean isOverLimit() {
        return getCharCount() >= paiMdMaxChars;
    }

    /**
     * 获取容量状态摘要（供 read_pai_md 工具返回给 Agent）。
     */
    public String getCapacityStatus() {
        int current = getCharCount();
        int max = getMaxChars();
        double ratio = max == 0 ? 0 : (double) current / max;
        String status;
        if (ratio >= 1.0) {
            status = "已超过上限，必须整合后再添加新条目";
        } else if (ratio >= integrateThreshold) {
            status = "已超过 " + (int) (integrateThreshold * 100) + "% 阈值，建议整合后再添加新条目";
        } else {
            status = "容量充足";
        }
        return String.format("PAI.md 容量: %d / %d 字符 (%.0f%%) - %s",
                current, max, ratio * 100, status);
    }

    /**
     * 获取所有已加载的 PAI.md 文件路径（供 suggest_pai_md 工具决定写入哪个文件）。
     */
    public List<Path> getLoadedFiles() {
        List<Path> files = new ArrayList<>();
        for (MemorySource source : sources()) {
            if (Files.isRegularFile(source.path())) {
                files.add(source.path());
            }
        }
        return files;
    }

    /**
     * 获取建议写入的 PAI.md 文件路径（优先项目级，其次用户级）。
     * 供 suggest_pai_md 工具决定写入目标。
     */
    public Path getSuggestTarget() {
        // 优先项目级 PAI.md
        Path projectPaiMd = projectRoot.resolve("PAI.md");
        if (Files.isRegularFile(projectPaiMd)) {
            return projectPaiMd;
        }
        // 其次项目级 .paicli/PAI.md
        Path dotPaiMd = projectRoot.resolve(".paicli").resolve("PAI.md");
        if (Files.isRegularFile(dotPaiMd)) {
            return dotPaiMd;
        }
        // 其次本地覆盖
        Path localPaiMd = projectRoot.resolve("PAI.local.md");
        if (Files.isRegularFile(localPaiMd)) {
            return localPaiMd;
        }
        // 默认项目级 PAI.md（即使不存在，也建议创建）
        return projectPaiMd;
    }

    private record MemorySource(Path path, Path importRoot) {
        private MemorySource {
            path = path.toAbsolutePath().normalize();
            importRoot = importRoot.toAbsolutePath().normalize();
        }
    }
}
