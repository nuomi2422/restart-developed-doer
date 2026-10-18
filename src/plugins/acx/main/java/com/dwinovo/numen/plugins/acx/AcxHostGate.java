package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxGameThreadGate;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link AcxGameThreadGate} 的宿主实现（契约 ACX-G1）。
 *
 * <p>已经在服务端线程 → 直接执行；在 AC 执行器线程 → 提交到服务端线程并等结果。
 * 服务端不在跑时（关服/单测）退化为直接执行 —— 此时世界读写本来也不成立。</p>
 */
public final class AcxHostGate implements AcxGameThreadGate {

    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    private final long timeoutMs;

    public AcxHostGate() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public AcxHostGate(long timeoutMs) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    @Override
    public AcxStepOutcome call(Callable<AcxStepOutcome> task) throws Exception {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isSameThread()) {
            return task.call();
        }
        // 1.21.1 的 MinecraftServer 只有 execute(Runnable)，没有 submit(Callable)；
        // 用 CompletableFuture 手动把结果带回来。
        CompletableFuture<AcxStepOutcome> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
