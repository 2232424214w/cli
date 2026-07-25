package com.bettercli.subagent;

import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量路由 LLM：基于各 Custom SubAgent 的 description 判断用户意图，
 * 命中则跳过主 Agent 决策轮次，直接以该子 Agent 身份响应（fail-open）。
 *
 * <p>门控：
 * <ul>
 *   <li>开关 {@code bettercli.subagent.router.enabled} / {@code BETTERCLI_SUBAGENT_ROUTER_ENABLED}</li>
 *   <li>最低置信度 {@code bettercli.subagent.router.min.confidence}（默认 0.70）</li>
 *   <li>可选专用模型 {@code bettercli.subagent.router.provider} / {@code .model}（未配或建失败回退主模型）</li>
 *   <li>用户前缀强制主 Agent：{@code @main} / {@code /main} / {@code 主agent:} 等</li>
 * </ul>
 */
public final class CustomSubAgentRouter {

    private static final Logger log = LoggerFactory.getLogger(CustomSubAgentRouter.class);
    private static final Pattern CONFIDENCE_LINE = Pattern.compile(
            "^\\s*([A-Za-z0-9_\\-]+)\\s*[|,/\\s]\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)\\s*$");
    private static final Pattern BYPASS_PREFIX = Pattern.compile(
            "(?i)^(?:@main\\b|/main\\b|主\\s*agent\\s*[:：]|不路由\\s*[:：]|force[-_]?main\\b)\\s*(.*)$",
            Pattern.DOTALL);

    private CustomSubAgentRouter() {
    }

    public record RouteDecision(String agentName, double confidence) {
        public RouteDecision {
            if (agentName == null || agentName.isBlank()) {
                throw new IllegalArgumentException("agentName 不能为空");
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));
        }
    }

    /** 强制主 Agent：剥掉前缀后的用户消息；未旁路时 message 为原文。 */
    public record BypassResult(boolean bypassRouter, String message) {
    }

    public static boolean isEnabled() {
        String raw = firstConfig("bettercli.subagent.router.enabled", "BETTERCLI_SUBAGENT_ROUTER_ENABLED");
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(raw.trim()) && !"0".equals(raw.trim());
    }

    /**
     * 解析路由用 LLM：可配专用 provider/model 省成本；未配或创建失败则回退 {@code fallback}。
     */
    public static LlmClient resolveClient(LlmClient fallback, BetterCliConfig config) {
        if (config == null) {
            return fallback;
        }
        String provider = firstConfig("bettercli.subagent.router.provider",
                "BETTERCLI_SUBAGENT_ROUTER_PROVIDER");
        if (provider == null || provider.isBlank()) {
            return fallback;
        }
        String model = firstConfig("bettercli.subagent.router.model",
                "BETTERCLI_SUBAGENT_ROUTER_MODEL");
        LlmClient client = LlmClientFactory.create(provider.trim(), config,
                model == null || model.isBlank() ? null : model.trim());
        if (client == null) {
            log.warn("Custom SubAgent router provider={} 不可用，回退主模型", provider.trim());
            return fallback;
        }
        log.debug("Custom SubAgent router using provider={} model={}",
                client.getProviderName(), client.getModelName());
        return client;
    }

    /** 默认 0.70；非法配置回退默认。 */
    public static double minConfidence() {
        String raw = firstConfig("bettercli.subagent.router.min.confidence",
                "BETTERCLI_SUBAGENT_ROUTER_MIN_CONFIDENCE");
        if (raw == null || raw.isBlank()) {
            return 0.70;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (v < 0 || v > 1) {
                return 0.70;
            }
            return v;
        } catch (NumberFormatException e) {
            return 0.70;
        }
    }

    public static BypassResult detectBypass(String userMessage) {
        if (userMessage == null) {
            return new BypassResult(false, "");
        }
        String trimmed = userMessage.trim();
        Matcher m = BYPASS_PREFIX.matcher(trimmed);
        if (m.matches()) {
            String rest = m.group(1) == null ? "" : m.group(1).trim();
            return new BypassResult(true, rest.isEmpty() ? trimmed : rest);
        }
        return new BypassResult(false, trimmed);
    }

    /**
     * @param stickyHint 上一轮路由命中的子 Agent（可 null），用于短跟进延续
     * @return 通过置信度门控的命中；否则 empty
     */
    public static Optional<RouteDecision> route(String userMessage, LlmClient client,
                                                List<CustomSubAgentDefinition> agents,
                                                String stickyHint) {
        if (!isEnabled() || client == null || agents == null || agents.isEmpty()) {
            return Optional.empty();
        }
        BypassResult bypass = detectBypass(userMessage);
        if (bypass.bypassRouter()) {
            log.info("Custom SubAgent router bypassed by user prefix");
            return Optional.empty();
        }
        String message = bypass.message();
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        try {
            String prompt = buildPrompt(message, agents, stickyHint);
            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user(prompt)),
                    null);
            RouteDecision decision = parseDecision(response == null ? null : response.content(), agents);
            if (decision == null) {
                log.debug("Custom SubAgent router miss");
                return Optional.empty();
            }
            double min = minConfidence();
            if (decision.confidence() < min) {
                log.info("Custom SubAgent router low confidence: name={} confidence={} min={}",
                        decision.agentName(), decision.confidence(), min);
                CustomSubAgentAudit.record("SUBAGENT_ROUTE_REJECTED", decision.agentName(), null, null,
                        "confidence=" + decision.confidence() + " < " + min);
                return Optional.empty();
            }
            log.info("Custom SubAgent router hit: {} confidence={}",
                    decision.agentName(), decision.confidence());
            CustomSubAgentAudit.record("SUBAGENT_ROUTED", decision.agentName(), null, null,
                    "confidence=" + decision.confidence() + " | " + preview(message, 100));
            return Optional.of(decision);
        } catch (Exception e) {
            log.warn("Custom SubAgent router failed, fall through to main Agent: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 兼容旧调用：无 sticky。 */
    public static Optional<String> route(String userMessage, LlmClient client,
                                         List<CustomSubAgentDefinition> agents) {
        return route(userMessage, client, agents, null).map(RouteDecision::agentName);
    }

    static String buildPrompt(String userMessage, List<CustomSubAgentDefinition> agents, String stickyHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是路由分类器。判断用户消息是否应直接交给某个专属子 Agent（跳过通用主 Agent）。\n");
        sb.append("仅当意图与某个子 Agent 的 description 明确匹配时才选中；模糊/通用编程任务选 NONE。\n");
        sb.append("只回复一行，格式严格为：\n");
        sb.append("  <name>|<confidence>\n");
        sb.append("或 NONE\n");
        sb.append("其中 confidence 为 0.0~1.0（有把握用 ≥0.8，勉强匹配用 0.5~0.7）。不要解释。\n\n");
        if (stickyHint != null && !stickyHint.isBlank()) {
            sb.append("上一轮曾路由到：").append(stickyHint.trim())
                    .append("。若本条是短跟进且意图未明显切换，可继续选它并给较高 confidence。\n\n");
        }
        sb.append("可选子 Agent：\n");
        for (CustomSubAgentDefinition a : agents) {
            sb.append("- name=").append(a.name());
            if (a.description() != null && !a.description().isBlank()) {
                sb.append(" | description=").append(a.description().trim());
            }
            sb.append('\n');
        }
        sb.append("\n用户消息：\n").append(userMessage);
        return sb.toString();
    }

    static RouteDecision parseDecision(String raw, List<CustomSubAgentDefinition> agents) {
        if (raw == null || raw.isBlank() || agents == null || agents.isEmpty()) {
            return null;
        }
        String line = raw.trim().lines().findFirst().orElse("").trim();
        if (line.isEmpty()) {
            return null;
        }
        if (line.startsWith("`") && line.endsWith("`") && line.length() > 2) {
            line = line.substring(1, line.length() - 1).trim();
        }
        String upper = line.toUpperCase(Locale.ROOT);
        if ("NONE".equals(upper) || "NULL".equals(upper) || "N/A".equals(upper)
                || "-".equals(line) || "无".equals(line)) {
            return null;
        }

        Matcher conf = CONFIDENCE_LINE.matcher(line);
        if (conf.matches()) {
            String nameToken = conf.group(1);
            double confidence = Double.parseDouble(conf.group(2));
            String resolved = resolveName(nameToken, agents);
            return resolved == null ? null : new RouteDecision(resolved, confidence);
        }

        // 兼容旧格式：整行仅为精确 name（避免说明性句子 contains 误路由）
        String resolved = resolveName(line, agents);
        if (resolved != null) {
            return new RouteDecision(resolved, 0.85);
        }
        return null;
    }

    /** 兼容旧测试：解析 name（无置信度时默认 0.85）。 */
    static String parseName(String raw, List<CustomSubAgentDefinition> agents) {
        RouteDecision d = parseDecision(raw, agents);
        return d == null ? null : d.agentName();
    }

    private static String resolveName(String token, List<CustomSubAgentDefinition> agents) {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (CustomSubAgentDefinition a : agents) {
            if (a.name().equalsIgnoreCase(token.trim())) {
                return a.name();
            }
        }
        return null;
    }

    private static String firstConfig(String property, String env) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(env);
        }
        return raw;
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
