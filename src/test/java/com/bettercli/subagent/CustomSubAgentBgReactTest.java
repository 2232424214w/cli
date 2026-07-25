package com.bettercli.subagent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomSubAgentBgReactTest {

    @Test
    void formatContainsFenceAndAction() {
        String text = CustomSubAgentCompletionNotice.format(new CustomSubAgentCompletionEvent(
                "parent", "sub_1", "code-reviewer", "call_1", "review Foo",
                true, false, "LGTM"));
        assertTrue(text.contains(CustomSubAgentCompletionNotice.RUNTIME_PREFIX));
        assertTrue(text.contains("status: ✅ done"));
        assertTrue(text.contains(CustomSubAgentCompletionNotice.BEGIN_RESULT));
        assertTrue(text.contains("LGTM"));
        assertTrue(text.contains("Action:"));
        assertTrue(CustomSubAgentCompletionNotice.isCompletionNotice(text));
    }

    @Test
    void cancelledStatus() {
        String text = CustomSubAgentCompletionNotice.format(new CustomSubAgentCompletionEvent(
                "p", "s", "a", null, "t", false, true, "用户取消"));
        assertTrue(text.contains("❌ cancelled"));
    }

    @Test
    void skipsWhenWriteNotNewerThanLastStart() throws Exception {
        BgReactCoordinator coord = new BgReactCoordinator();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstDone = new CountDownLatch(1);

        coord.markSessionWrite("c1");
        coord.enqueue("c1", new BgReactCoordinator.BgReactTask() {
            @Override
            public String run() {
                runs.incrementAndGet();
                firstDone.countDown();
                return "one";
            }
        });
        assertTrue(firstDone.await(3, TimeUnit.SECONDS));

        coord.enqueue("c1", new BgReactCoordinator.BgReactTask() {
            @Override
            public String run() {
                runs.incrementAndGet();
                return "two";
            }
        });
        Thread.sleep(300);
        assertEquals(1, runs.get());

        coord.markSessionWrite("c1");
        CountDownLatch thirdDone = new CountDownLatch(1);
        coord.enqueue("c1", new BgReactCoordinator.BgReactTask() {
            @Override
            public String run() {
                runs.incrementAndGet();
                thirdDone.countDown();
                return "three";
            }
        });
        assertTrue(thirdDone.await(3, TimeUnit.SECONDS));
        assertEquals(2, runs.get());
        coord.shutdown();
    }
}
