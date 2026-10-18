package com.dwinovo.numen.worldstate.core.refresh;

import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.RefreshStatus;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.ObservationStore;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集调度：把「去取」的成本管住——同类请求合并、范围与体积预算、超时/取消/排队。
 *
 * <h2>两种执行方式</h2>
 * <ul>
 *   <li>{@link #refreshNow} —— 在当前线程同步采集。<b>宿主侧工具走这条</b>：工具已在服务端线程，
 *       采集器读真实世界必须在此线程完成。</li>
 *   <li>{@link #submit} —— 提交到注入的 {@link Executor} 异步采集，相同键的并发请求合并成一次。
 *       适合纯逻辑采集器与离线单测。宿主侧若用它，采集器自己必须处理线程正确性。</li>
 * </ul>
 *
 * <p>体积预算：请求体积超过采集器声明的 {@link WorldProvider#maxVolume()} 时明确拒绝
 * （{@code BUDGET_EXCEEDED}），请调用方缩小范围——不在一次调用里把服务端卡住。分批执行/返回任务 ID
 * 留待后续版本；这里先把"拒绝而不是硬跑"这条线画清楚。
 *
 * <p>不再强制加载或生成远处区块——那是采集器的责任，本调度只负责预算与去重。
 */
public final class RefreshService {

    private final ProviderRegistry providers;
    private final ObservationStore store;
    private final Executor executor;
    /** 修订号：按请求被采纳的先后单调递增，让迟到的旧刷新无法覆盖新观测。 */
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, CompletableFuture<RefreshResult>> inFlight = new ConcurrentHashMap<>();

    public RefreshService(ProviderRegistry providers, ObservationStore store, Executor executor) {
        this.providers = providers;
        this.store = store;
        this.executor = executor;
    }

    /** 同步采集（宿主工具用；必须在服务端线程调用，采集器要读真实世界）。 */
    public RefreshResult refreshNow(CollectionContext ctx, ObservationRequest request) {
        return doRefreshSafe(ctx, request, sequence.incrementAndGet());
    }

    /**
     * 异步采集，相同观测键的并发请求合并成一次。
     *
     * @param timeoutMillis 大于 0 时，超时以 {@code TIMED_OUT} 完成（底层任务可能仍在跑）
     * @return 可直接 {@code cancel(true)} 的 future
     */
    public CompletableFuture<RefreshResult> submit(CollectionContext ctx, ObservationRequest request,
                                                   long timeoutMillis) {
        String key = request.keyFor(ctx.worldId()).canonical();
        AtomicBoolean owner = new AtomicBoolean(false);
        CompletableFuture<RefreshResult> future = inFlight.computeIfAbsent(key, k -> {
            owner.set(true);
            return new CompletableFuture<>();
        });
        if (owner.get()) {
            long revision = sequence.incrementAndGet();
            CompletableFuture.runAsync(() -> {
                try {
                    future.complete(doRefreshSafe(ctx, request, revision));
                } finally {
                    inFlight.remove(key, future);
                }
            }, executor);
            if (timeoutMillis > 0) {
                CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS)
                        .execute(() -> future.complete(RefreshResult.timedOut(timeoutMillis)));
            }
        }
        return future;
    }

    /** 采集器是否已为该主题注册。 */
    public boolean hasProvider(Topic topic) {
        return providers.byTopic(topic).isPresent();
    }

    /** 采集器绝不让异常逃逸到调度之外——所有失败都落成一个明确的 {@link RefreshResult}。 */
    private RefreshResult doRefreshSafe(CollectionContext ctx, ObservationRequest request, long revision) {
        long start = System.currentTimeMillis();
        if (ctx == null || request == null) {
            return RefreshResult.failed(FailReason.INVALID_REQUEST, "missing context or request", elapsed(start));
        }
        Optional<WorldProvider> maybe = providers.resolve(request);
        if (maybe.isEmpty()) {
            return RefreshResult.failed(FailReason.NO_PROVIDER,
                    "no provider for " + request.describe(), elapsed(start));
        }
        WorldProvider provider = maybe.get();
        if (provider.maxVolume() > 0 && request.volume() > provider.maxVolume()) {
            return RefreshResult.failed(FailReason.BUDGET_EXCEEDED,
                    "request volume " + request.volume() + " exceeds limit " + provider.maxVolume()
                            + "; narrow the range", elapsed(start));
        }
        try {
            ProviderResult result = provider.collect(ctx, request);
            ObservationKey key = request.keyFor(ctx.worldId());
            Observation observation = new Observation(key, result.payload(), provider.id(), Origin.DIRECT,
                    ctx.gameTick(), ctx.nowMs(), result.coverage(), Freshness.CURRENT, revision,
                    provider.dataVersion());
            store.put(observation);
            return RefreshResult.ok(observation, elapsed(start));
        } catch (ProviderException e) {
            return RefreshResult.failed(e.reason(), e.getMessage() == null ? e.reason().name() : e.getMessage(),
                    elapsed(start));
        } catch (RuntimeException e) {
            return RefreshResult.fail(RefreshStatus.FAILED, FailReason.PROVIDER_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(start));
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
