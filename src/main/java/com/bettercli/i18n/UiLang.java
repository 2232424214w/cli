package com.bettercli.i18n;

import java.util.Locale;

/**
 * UI / LLM 界面语言。默认中文；可用 {@code /lang}、{@code BETTERCLI_UI_LANG}、
 * {@code -Dbettercli.ui.lang} 或 {@code ~/.bettercli/config.json} 的 {@code uiLanguage} 切换。
 */
public enum UiLang {
    ZH,
    EN;

    public static UiLang parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZH;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "en", "english", "en-us", "en_us" -> EN;
            case "zh", "zh-cn", "zh_cn", "cn", "chinese", "中文" -> ZH;
            default -> ZH;
        };
    }

    /**
     * 优先级：系统属性 {@code bettercli.ui.lang} &gt; 环境变量 {@code BETTERCLI_UI_LANG}
     * &gt; 配置文件值 &gt; 默认 zh。
     */
    public static UiLang resolve(String configValue) {
        String prop = System.getProperty("bettercli.ui.lang");
        if (prop != null && !prop.isBlank()) {
            return parse(prop);
        }
        String env = System.getenv("BETTERCLI_UI_LANG");
        if (env != null && !env.isBlank()) {
            return parse(env);
        }
        return parse(configValue);
    }

    public String code() {
        return this == EN ? "en" : "zh";
    }

    public String displayName() {
        return this == EN ? "English" : "中文";
    }
}
