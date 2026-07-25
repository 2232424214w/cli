package com.bettercli.subagent;

import com.bettercli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentSessionStoreTest {

    @TempDir
    Path temp;

    @Test
    void startCheckpointFinishAndResumeLookup() {
        CustomSubAgentSessionStore store = new CustomSubAgentSessionStore(temp);
        store.start("sub_demo_1", "code-reviewer", "parent", "review Foo", "delegate");
        store.checkpoint("sub_demo_1", List.of(
                LlmClient.Message.system("sys"),
                LlmClient.Message.user("review Foo"),
                LlmClient.Message.assistant("looking")));
        CustomSubAgentSessionStore.SessionRecord mid = store.load("sub_demo_1");
        assertNotNull(mid);
        assertEquals(CustomSubAgentSessionStore.Status.RUNNING, mid.status());
        assertEquals(3, mid.messages().size());
        assertTrue(mid.messages().stream().anyMatch(m -> "user".equals(m.role())));

        store.finish("sub_demo_1", CustomSubAgentSessionStore.Status.CANCELLED, "cancelled",
                List.of(LlmClient.Message.user("review Foo"), LlmClient.Message.assistant("partial")));
        assertEquals(CustomSubAgentSessionStore.Status.CANCELLED, store.load("sub_demo_1").status());
        assertNotNull(store.latestResumable());
        assertTrue(store.formatList(5).contains("sub_demo_1"));
    }

    @Test
    void finishWithEmptyMessagesKeepsCheckpointHistory() {
        CustomSubAgentSessionStore store = new CustomSubAgentSessionStore(temp);
        store.start("sub_keep_1", "code-reviewer", "parent", "review", "delegate");
        store.checkpoint("sub_keep_1", List.of(
                LlmClient.Message.user("review"),
                LlmClient.Message.assistant("half done")));
        store.finish("sub_keep_1", CustomSubAgentSessionStore.Status.CANCELLED, null, List.of());
        CustomSubAgentSessionStore.SessionRecord rec = store.load("sub_keep_1");
        assertEquals(CustomSubAgentSessionStore.Status.CANCELLED, rec.status());
        assertEquals(2, rec.messages().size());
        assertEquals("half done", rec.messages().get(1).content());
    }
}
