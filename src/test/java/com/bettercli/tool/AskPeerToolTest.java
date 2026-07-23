package com.bettercli.tool;

import com.bettercli.agent.SharedState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ask_peer 工具（阶段D p2p，对标 Claude Code agent teams：worker 间直接消息）。
 */
class AskPeerToolTest {

    @Test
    void askPeerPostsMessageToSharedState() {
        ToolRegistry registry = new ToolRegistry();
        SharedState state = new SharedState();
        registry.setSharedState(state);
        registry.setCurrentWorkerName("worker-1");

        String result = registry.executeTool("ask_peer",
                "{\"to\":\"worker-2\",\"message\":\"接口定了吗\"}");

        assertTrue(result.contains("已向 worker-2"));
        assertTrue(result.contains("接口定了吗"));
        assertEquals(1, state.getPeerMessages().size());
        assertEquals("worker-1", state.getPeerMessages().get(0).from());
        assertEquals("worker-2", state.getPeerMessages().get(0).to());
    }

    @Test
    void askPeerBroadcastsWhenToBlank() {
        ToolRegistry registry = new ToolRegistry();
        SharedState state = new SharedState();
        registry.setSharedState(state);
        registry.setCurrentWorkerName("worker-1");

        String result = registry.executeTool("ask_peer",
                "{\"message\":\"全员注意\"}");

        assertTrue(result.contains("所有 worker"));
        assertEquals("", state.getPeerMessages().get(0).to());
    }

    @Test
    void askPeerFailsWithoutSharedState() {
        ToolRegistry registry = new ToolRegistry();
        // 不注入 sharedState
        registry.setCurrentWorkerName("worker-1");

        String result = registry.executeTool("ask_peer",
                "{\"to\":\"worker-2\",\"message\":\"hi\"}");

        assertTrue(result.contains("未初始化"));
        assertTrue(result.contains("Multi-Agent"));
    }

    @Test
    void askPeerFailsWithoutCurrentWorkerName() {
        ToolRegistry registry = new ToolRegistry();
        SharedState state = new SharedState();
        registry.setSharedState(state);
        // 不设置 currentWorkerName

        String result = registry.executeTool("ask_peer",
                "{\"to\":\"worker-2\",\"message\":\"hi\"}");

        assertTrue(result.contains("当前 worker 名未设置"));
    }

    @Test
    void askPeerFailsWithBlankMessage() {
        ToolRegistry registry = new ToolRegistry();
        SharedState state = new SharedState();
        registry.setSharedState(state);
        registry.setCurrentWorkerName("worker-1");

        String result = registry.executeTool("ask_peer",
                "{\"to\":\"worker-2\",\"message\":\"   \"}");

        assertTrue(result.contains("message 不能为空"));
    }

    @Test
    void askPeerNotExposedWhenSharedStateNull() {
        ToolRegistry registry = new ToolRegistry();
        // sharedState 未注入：ask_peer 不应出现在工具定义里（避免主 ReAct 看到调用即失败的工具）
        boolean exposed = registry.getToolDefinitions().stream()
                .anyMatch(t -> "ask_peer".equals(t.name()));
        assertFalse(exposed, "sharedState 未注入时 ask_peer 不应暴露给 LLM");
    }

    @Test
    void askPeerExposedWhenSharedStateInjected() {
        ToolRegistry registry = new ToolRegistry();
        registry.setSharedState(new SharedState());
        boolean exposed = registry.getToolDefinitions().stream()
                .anyMatch(t -> "ask_peer".equals(t.name()));
        assertTrue(exposed, "sharedState 注入后 ask_peer 应暴露给 LLM");
    }

    @Test
    void askPeerRespectsWhitelist() {
        ToolRegistry registry = new ToolRegistry();
        registry.setSharedState(new SharedState());
        // 白名单不含 ask_peer 时不应暴露
        boolean exposed = registry.getToolDefinitions(java.util.Set.of("read_file", "grep_code")).stream()
                .anyMatch(t -> "ask_peer".equals(t.name()));
        assertFalse(exposed, "白名单不含 ask_peer 时不应暴露");
    }
}
