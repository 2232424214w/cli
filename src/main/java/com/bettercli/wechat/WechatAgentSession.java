package com.bettercli.wechat;

import com.bettercli.agent.Agent;
import com.bettercli.config.BetterCliConfig;
import com.bettercli.llm.LlmClient;
import com.bettercli.llm.LlmClientFactory;
import com.bettercli.render.Renderer;
import com.bettercli.runtime.CancellationContext;
import com.bettercli.runtime.CancellationToken;
import com.bettercli.subagent.CustomSubAgentBootstrap;
import com.bettercli.subagent.CustomSubAgentRouter;
import com.bettercli.subagent.CustomSubAgentRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WechatAgentSession implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bettercli-wechat-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final WechatTerminalRenderer renderer;
    private final Agent agent;
    private final BetterCliConfig config;
    private final LlmClient llmClient;
    private final CustomSubAgentRunner customSubAgentRunner;
    private final CustomSubAgentBootstrap.Bundle subagentBundle;
    private String lastRoutedSubAgent;
    private Future<String> running;
    private CancellationToken runningToken;

    public WechatAgentSession(WechatAccount account, WechatMessageSender sender) {
        this(account, sender, null);
    }

    public WechatAgentSession(WechatAccount account, WechatMessageSender sender, Renderer localRenderer) {
        Objects.requireNonNull(account, "account");
        this.config = BetterCliConfig.load();
        this.llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            throw new IllegalStateException("未找到可用的 API Key，无法启动微信 Agent 会话");
        }
        Path workspace = Path.of(account.workspace() == null || account.workspace().isBlank() ? "." : account.workspace())
                .toAbsolutePath().normalize();
        WechatPolicyConfig policyConfig = WechatPolicyConfig.forWorkspace(workspace);
        WechatToolRegistry registry = new WechatToolRegistry(new WechatPolicyDecider(policyConfig));
        registry.setProjectPath(workspace.toString());
        this.renderer = new WechatTerminalRenderer(localRenderer, sender);
        this.agent = new Agent(llmClient, registry);
        this.agent.setRenderer(renderer);
        this.agent.setReturnFinalResponseWhenStreamed(true);

        this.subagentBundle = CustomSubAgentBootstrap.create(workspace);
        this.customSubAgentRunner = subagentBundle.runner();
        this.agent.setCustomSubAgentRunner(customSubAgentRunner);
    }

    public synchronized boolean isRunning() {
        return running != null && !running.isDone();
    }

    public synchronized boolean hasCompletedRun() {
        return running != null && running.isDone();
    }

    public synchronized Future<String> submit(String prompt) {
        if (isRunning()) {
            throw new IllegalStateException("当前已有微信任务在运行");
        }
        renderer.resetWechatStream();
        runningToken = CancellationContext.startRun();
        final String raw = prompt == null ? "" : prompt;
        Callable<String> task = () -> dispatch(raw);
        running = executor.submit(task);
        return running;
    }

    private String dispatch(String prompt) {
        // 微信默认关闭入站路由（省成本/避误触），可用 BETTERCLI_WECHAT_SUBAGENT_ROUTER=true 开启
        boolean wechatRouter = wechatRouterEnabled();
        LlmClient routerClient = CustomSubAgentRouter.resolveClient(llmClient, config);
        CustomSubAgentRouter.IngressDecision ingress;
        if (CustomSubAgentRouter.detectDirectDesignate(prompt).isPresent()) {
            ingress = CustomSubAgentRouter.resolveIngress(
                    prompt, routerClient, subagentBundle.registry().all(), lastRoutedSubAgent);
        } else if (wechatRouter) {
            ingress = CustomSubAgentRouter.resolveIngress(
                    prompt, routerClient, subagentBundle.registry().all(), lastRoutedSubAgent);
        } else {
            CustomSubAgentRouter.BypassResult bypass = CustomSubAgentRouter.detectBypass(prompt);
            String effective = bypass.message() == null || bypass.message().isBlank() ? prompt : bypass.message();
            if (bypass.bypassRouter()) {
                lastRoutedSubAgent = null;
            }
            ingress = CustomSubAgentRouter.IngressDecision.main(effective, bypass.bypassRouter());
        }

        if (ingress.clearSticky()) {
            lastRoutedSubAgent = null;
        }

        if (ingress.kind() == CustomSubAgentRouter.IngressDecision.Kind.DIRECT
                || ingress.kind() == CustomSubAgentRouter.IngressDecision.Kind.ROUTED) {
            String name = ingress.agentName();
            if (subagentBundle.registry().find(name) == null) {
                lastRoutedSubAgent = null;
                return "未找到子 Agent \"" + name + "\"。可用：请在绑定 workspace 的 .bettercli/agents/ 下配置，"
                        + "或在 CLI 用 /subagent list 查看。";
            }
            lastRoutedSubAgent = name;
            List<LlmClient.Message> history = agent.recentDialogueMessages(12);
            String answer = customSubAgentRunner.runDirect(
                    name, ingress.effectiveMessage(), llmClient,
                    agent.getToolRegistry(), null, null, history, null);
            agent.recordExternalTurn(ingress.effectiveMessage(), answer, name);
            return answer;
        }
        return agent.run(ingress.effectiveMessage());
    }

    /** 微信通道路由默认关；显式 true/1 开启。硬指定 `/subagent:name` 始终可用。 */
    static boolean wechatRouterEnabled() {
        String raw = System.getProperty("bettercli.wechat.subagent.router");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("BETTERCLI_WECHAT_SUBAGENT_ROUTER");
        }
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    public synchronized String awaitCurrent() {
        if (running == null) {
            return "";
        }
        try {
            String result = running.get();
            if (renderer.consumeSentContentFlag()) {
                return "";
            }
            return result == null ? "" : result;
        } catch (CancellationException e) {
            customSubAgentRunner.cancelAllPending();
            return "已取消当前任务。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            customSubAgentRunner.cancelAllPending();
            return "当前任务被中断。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return "执行失败: " + (cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage());
        } finally {
            if (runningToken != null) {
                CancellationContext.clear(runningToken);
                runningToken = null;
            }
            running = null;
        }
    }

    public synchronized void cancel() {
        if (runningToken != null) {
            runningToken.cancel();
        }
        if (customSubAgentRunner != null) {
            customSubAgentRunner.cancelAllPending();
        }
        if (running != null) {
            running.cancel(true);
        }
    }

    public void clear() {
        lastRoutedSubAgent = null;
        agent.clearHistory();
    }

    public String compact() {
        Agent.CompactionResult result = agent.compactHistoryNow();
        if (result.error() != null && !result.error().isBlank()) {
            return "手动压缩失败: " + result.error();
        }
        if (result.compacted()) {
            return "已手动压缩历史上下文: " + result.beforeTokens() + " -> " + result.afterTokens() + " tokens";
        }
        return "当前没有需要压缩的历史上下文";
    }

    public String status() {
        return agent.currentStatus(isRunning() ? "running" : "idle").toString();
    }

    @Override
    public void close() {
        cancel();
        executor.shutdownNow();
    }
}
