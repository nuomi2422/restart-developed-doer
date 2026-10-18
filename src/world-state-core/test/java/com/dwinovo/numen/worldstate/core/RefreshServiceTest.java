package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.RefreshStatus;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshServiceTest {

    private ExecutorService executor;
    private InMemoryObservationStore store;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        store = new InMemoryObservationStore();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static TestSupport.Ctx ctx() {
        return new TestSupport.Ctx("live", 42L, 1_000L);
    }

    private static ObservationRequest sheepBox() {
        return ObservationRequest.entities("minecraft:overworld", Bounds.of(0, 64, 0, 3, 64, 3),
                "minecraft:sheep", null);
    }

    @Test
    void refreshNowWritesObservation() {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        RefreshResult result = service.refreshNow(ctx(), sheepBox());

        assertTrue(result.success());
        assertEquals(1, provider.invocations.get());
        EntityCountPayload payload = (EntityCountPayload) result.observation().payload();
        assertEquals(2, payload.count());
        assertEquals("fake-entities", result.observation().source());
        assertEquals(7, result.observation().dataVersion());
        assertTrue(store.get(result.observation().key()).isPresent());
    }

    @Test
    void noProviderIsExplicitFailure() {
        RefreshService service = new RefreshService(new ProviderRegistry(), store, executor);
        RefreshResult result = service.refreshNow(ctx(), sheepBox());
        assertEquals(RefreshStatus.FAILED, result.status());
        assertEquals(FailReason.NO_PROVIDER, result.reason());
        assertEquals(0, store.size());
    }

    @Test
    void budgetExceededRejectsWithoutCollecting() {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        provider.maxVolume = 10L;
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        ObservationRequest big = ObservationRequest.entities("minecraft:overworld",
                Bounds.of(0, 0, 0, 20, 20, 20), "minecraft:sheep", null);
        RefreshResult result = service.refreshNow(ctx(), big);

        assertEquals(FailReason.BUDGET_EXCEEDED, result.reason());
        assertEquals(0, provider.invocations.get());
    }

    @Test
    void chunkUnloadedKeepsPreviousObservation() {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        assertTrue(service.refreshNow(ctx(), sheepBox()).success());
        int sizeBefore = store.size();

        provider.throwNext = TestSupport.unloaded();
        RefreshResult failed = service.refreshNow(ctx(), sheepBox());

        assertEquals(FailReason.CHUNK_UNLOADED, failed.reason());
        assertEquals(sizeBefore, store.size(), "失败不清空旧观测");
    }

    @Test
    void providerCrashMapsToProviderError() {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        provider.throwNext = new IllegalStateException("boom");
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        assertEquals(FailReason.PROVIDER_ERROR, service.refreshNow(ctx(), sheepBox()).reason());
    }

    @Test
    void concurrentDuplicateRequestsShareOneCollection() throws Exception {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        provider.sleepMillis = 300L;
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        CompletableFuture<RefreshResult> first = service.submit(ctx(), sheepBox(), 0L);
        CompletableFuture<RefreshResult> second = service.submit(ctx(), sheepBox(), 0L);
        assertSame(first, second, "同一观测键的并发请求应合并");

        assertTrue(first.get(5, TimeUnit.SECONDS).success());
        assertEquals(1, provider.invocations.get());
    }

    @Test
    void timeoutYieldsTimedOut() throws Exception {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        provider.sleepMillis = 500L;
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        RefreshResult result = service.submit(ctx(), sheepBox(), 50L).get(5, TimeUnit.SECONDS);
        assertEquals(RefreshStatus.TIMED_OUT, result.status());
    }

    @Test
    void cancellationIsObservable() {
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        provider.sleepMillis = 400L;
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        CompletableFuture<RefreshResult> future = service.submit(ctx(), sheepBox(), 0L);
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
    }
}
