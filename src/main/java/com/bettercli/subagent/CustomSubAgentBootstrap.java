package com.bettercli.subagent;

import java.nio.file.Path;

/**
 * Custom SubAgent 目录引导（CLI / 微信共用）。
 */
public final class CustomSubAgentBootstrap {

    private CustomSubAgentBootstrap() {
    }

    public record Bundle(
            CustomSubAgentRegistry registry,
            CustomSubAgentRunner runner,
            Path userAgentsDir,
            Path projectAgentsDir,
            Path agentsCacheDir
    ) {
    }

    public static Bundle create(Path projectRoot) {
        Path home = Path.of(System.getProperty("user.home"));
        Path agentsCacheDir = home.resolve(".bettercli/agents-cache");
        Path userAgentsDir = home.resolve(".bettercli/agents");
        Path root = projectRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
        Path projectAgentsDir = root.resolve(".bettercli/agents");
        try {
            new CustomSubAgentBuiltinExtractor(agentsCacheDir).extractAll();
        } catch (Exception ignored) {
            // 内置解压失败不阻断
        }
        CustomSubAgentRegistry registry = new CustomSubAgentRegistry(
                agentsCacheDir, userAgentsDir, projectAgentsDir);
        registry.reload();
        CustomSubAgentRunner runner = new CustomSubAgentRunner(registry);
        return new Bundle(registry, runner, userAgentsDir, projectAgentsDir, agentsCacheDir);
    }
}
