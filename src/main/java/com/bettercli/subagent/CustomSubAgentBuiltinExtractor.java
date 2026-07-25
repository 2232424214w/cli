package com.bettercli.subagent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 把 jar 内 {@code resources/agents/<name>/AGENT.md} 解压到
 * {@code ~/.bettercli/agents-cache/<name>/}。
 */
public final class CustomSubAgentBuiltinExtractor {

    private static final List<String> BUILTIN_AGENTS = List.of("code-reviewer", "researcher", "sql-analyzer");

    /**  bump 以强制覆盖已解压的内置 AGENT.md */
    public static final String CURRENT_VERSION = "1.2.0";

    private final Path cacheRoot;

    public CustomSubAgentBuiltinExtractor(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Path cacheRoot() {
        return cacheRoot;
    }

    public void extractAll() throws IOException {
        Files.createDirectories(cacheRoot);
        for (String name : BUILTIN_AGENTS) {
            extract(name);
        }
    }

    private void extract(String name) throws IOException {
        Path destDir = cacheRoot.resolve(name);
        Path versionFile = destDir.resolve(".version");
        if (Files.isRegularFile(versionFile)
                && CURRENT_VERSION.equals(Files.readString(versionFile).trim())) {
            return;
        }
        Files.createDirectories(destDir);
        String resource = "agents/" + name + "/AGENT.md";
        try (InputStream in = CustomSubAgentBuiltinExtractor.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("⚠️ 内置 Custom SubAgent 资源缺失: " + resource);
                return;
            }
            Files.copy(in, destDir.resolve("AGENT.md"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(versionFile, CURRENT_VERSION);
    }
}
