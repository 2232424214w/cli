package com.bettercli.runtime;

import java.util.concurrent.atomic.AtomicReference;

public final class CancellationContext {
    private static final AtomicReference<CancellationToken> CURRENT = new AtomicReference<>();
    private static final InheritableThreadLocal<CancellationToken> LOCAL = new InheritableThreadLocal<>();

    private CancellationContext() {
    }

    public static CancellationToken startRun() {
        CancellationToken token = new CancellationToken();
        CURRENT.set(token);
        LOCAL.set(token);
        return token;
    }

    public static CancellationToken current() {
        CancellationToken token = LOCAL.get();
        return token == null ? CURRENT.get() : token;
    }

    public static boolean isCancelled() {
        CancellationToken token = current();
        return token != null && token.isCancelled();
    }

    /**
     * 在线程池 worker 上绑定调用方当前的取消令牌（避免 InheritableThreadLocal 粘住旧 token）。
     * 任务结束务必 {@link #unbind()}。
     */
    public static void bind(CancellationToken token) {
        if (token == null) {
            LOCAL.remove();
        } else {
            LOCAL.set(token);
        }
    }

    /** 捕获当前线程所见的取消令牌，供提交到线程池后 {@link #bind}。 */
    public static CancellationToken capture() {
        return current();
    }

    public static void unbind() {
        LOCAL.remove();
    }

    public static void clear(CancellationToken token) {
        if (LOCAL.get() == token) {
            LOCAL.remove();
        }
        CURRENT.compareAndSet(token, null);
    }
}
