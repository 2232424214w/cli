package com.bettercli.subagent;

import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * bg-react 可选独立小模型（降本）；未配或创建失败回退主模型。
 *
 * <p>配置：{@code BETTERCLI_BG_REACT_PROVIDER} / {@code BETTERCLI_BG_REACT_MODEL}
 * （或 {@code -Dbettercli.bg.react.provider} / {@code .model}）。
 */
public final class BgReactClientResolver {

    private static final Logger log = LoggerFactory.getLogger(BgReactClientResolver.class);

    private BgReactClientResolver() {
    }

    public static LlmClient resolve(LlmClient fallback) {
        return resolve(fallback, BetterCliConfig.load());
    }

    public static LlmClient resolve(LlmClient fallback, BetterCliConfig config) {
        if (config == null) {
            return fallback;
        }
        String provider = firstConfig("bettercli.bg.react.provider", "BETTERCLI_BG_REACT_PROVIDER");
        if (provider == null || provider.isBlank()) {
            return fallback;
        }
        String model = firstConfig("bettercli.bg.react.model", "BETTERCLI_BG_REACT_MODEL");
        LlmClient client = LlmClientFactory.create(provider.trim(), config,
                model == null || model.isBlank() ? null : model.trim());
        if (client == null) {
            log.warn("bg-react provider={} 不可用，回退主模型", provider.trim());
            return fallback;
        }
        log.debug("bg-react using provider={} model={}",
                client.getProviderName(), client.getModelName());
        return client;
    }

    private static String firstConfig(String prop, String env) {
        String raw = System.getProperty(prop);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(env);
        }
        return raw;
    }
}
