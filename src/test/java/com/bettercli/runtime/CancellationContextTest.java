package com.bettercli.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationContextTest {

    @Test
    void childThreadKeepsRunTokenAfterParentClearsGlobalContext() throws Exception {
        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch parentCleared = new CountDownLatch(1);
        try {
            Future<Boolean> result = executor.submit(() -> {
                parentCleared.await();
                return CancellationContext.current() == token;
            });

            CancellationContext.clear(token);
            parentCleared.countDown();

            assertTrue(result.get());
        } finally {
            token.cancel();
            CancellationContext.clear(token);
            executor.shutdownNow();
        }
    }

    @Test
    void bindOverridesStickyInheritedTokenOnCachedWorker() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            // 先污染 worker：继承并粘住已取消的旧 token
            CancellationToken stale = CancellationContext.startRun();
            stale.cancel();
            pool.submit(() -> CancellationContext.isCancelled()).get();
            CancellationContext.clear(stale);

            CancellationToken fresh = CancellationContext.startRun();
            try {
                CancellationToken captured = CancellationContext.capture();
                AtomicReference<CancellationToken> seen = new AtomicReference<>();
                Future<Boolean> cancelled = pool.submit(() -> {
                    CancellationContext.bind(captured);
                    try {
                        seen.set(CancellationContext.current());
                        return CancellationContext.isCancelled();
                    } finally {
                        CancellationContext.unbind();
                    }
                });
                assertFalse(cancelled.get());
                assertSame(fresh, seen.get());
            } finally {
                CancellationContext.clear(fresh);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
