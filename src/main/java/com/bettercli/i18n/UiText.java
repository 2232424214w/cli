package com.bettercli.i18n;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 交互层文案（Banner / 状态栏 / Thinking / 提示）。默认中文。
 */
public final class UiText {

    private static final AtomicReference<UiLang> CURRENT = new AtomicReference<>(UiLang.ZH);

    private UiText() {
    }

    public static void setLang(UiLang lang) {
        CURRENT.set(lang == null ? UiLang.ZH : lang);
    }

    public static UiLang lang() {
        return CURRENT.get();
    }

    public static boolean isChinese() {
        return lang() == UiLang.ZH;
    }

    public static String tipsTitle() {
        return isChinese() ? "入门提示：" : "Tips for getting started:";
    }

    public static String tipCommands() {
        return isChinese()
                ? "1. 输入 " + emphasisPlaceholder() + " 查看命令，用 Tab 补全"
                : "1. Type " + emphasisPlaceholder() + " for commands and Tab completion";
    }

    /** Marker replaced by caller with AnsiStyle.emphasis("/"). */
    public static String slashMarker() {
        return "{{/}}";
    }

    public static String tipAsk() {
        return isChinese()
                ? "2. 直接提问、改代码或执行命令"
                : "2. Ask coding questions, edit code or run commands";
    }

    public static String tipAttach() {
        return isChinese()
                ? "3. 用 {{@path}} 或 {{@image:}} 附加上下文"
                : "3. Attach context with {{@path}} or {{@image:}}";
    }

    private static String emphasisPlaceholder() {
        return "{{/}}";
    }

    public static String inputRightPrompt() {
        return isChinese() ? "消息 / @路径 / @图片" : "message / @path / @image";
    }

    public static String thinkingLabel() {
        return isChinese() ? "思考中" : "Thinking";
    }

    public static String thinkingDots() {
        return isChinese() ? "思考中..." : "Thinking...";
    }

    public static String hitlMode(boolean hitlEnabled) {
        if (isChinese()) {
            return hitlEnabled ? "人工审批 Ctrl+Y 切自动" : "自动模式 Ctrl+Y 开审批";
        }
        return hitlEnabled ? "HITL Ctrl+Y for YOLO" : "YOLO Ctrl+Y to enable HITL";
    }

    public static String autoModelPrefix() {
        return isChinese() ? " 当前模型 · " : " Auto Model · ";
    }

    public static String phaseLabel(String phase) {
        String raw = phase == null || phase.isBlank() ? "idle" : phase.trim();
        if (!isChinese()) {
            return raw;
        }
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "idle" -> "空闲";
            case "running" -> "运行中";
            case "thinking" -> "思考中";
            case "tool", "tools" -> "工具中";
            case "planning" -> "规划中";
            case "waiting" -> "等待中";
            case "compacting" -> "压缩中";
            default -> {
                if (lower.startsWith("subagent:")) {
                    yield "子代理·" + raw.substring("subagent:".length());
                }
                if (lower.startsWith("sa:") ) {
                    yield "子代理·" + raw.substring(3);
                }
                // sa×N / saxN
                if (raw.length() >= 3 && lower.startsWith("sa")
                        && (raw.charAt(2) == '×' || raw.charAt(2) == 'x' || raw.charAt(2) == 'X')) {
                    yield "子代理×" + raw.substring(3);
                }
                yield raw;
            }
        };
    }

    public static String langStatusLine() {
        return isChinese()
                ? "当前界面语言：中文（/lang en 切换英文，/lang zh 切回中文）"
                : "UI language: English (/lang zh for Chinese, /lang en for English)";
    }

    public static String langSwitched(UiLang lang) {
        if (lang == UiLang.EN) {
            return "UI language set to English. LLM replies will follow English. Use /lang zh to switch back.";
        }
        return "已切换为中文界面。模型回复也会默认使用中文。可用 /lang en 切换英文。";
    }

    public static String langUsage() {
        return isChinese()
                ? "用法：/lang  |  /lang zh  |  /lang en"
                : "Usage: /lang  |  /lang zh  |  /lang en";
    }

    /** Injected into system prompt ## Language section. */
    public static String llmLanguagePolicy() {
        if (isChinese()) {
            return "请用中文回复用户。推理、计划、工具结果解释和最终回复都默认使用中文；"
                    + "只有代码、命令、文件名、API 名称和用户明确要求的外语内容保留原文。";
        }
        return "Reply to the user in English by default for reasoning, plans, tool explanations, and final answers. "
                + "Keep code, commands, filenames, API names, and explicitly requested non-English text unchanged.";
    }
}
