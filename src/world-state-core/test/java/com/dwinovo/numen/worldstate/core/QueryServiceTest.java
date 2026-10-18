package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.query.QueryService;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServiceTest {

    private InMemoryObservationStore store;
    private TestSupport.FakeEntitiesProvider provider;
    private QueryService query;
    private final long[] clock = {1_000L};

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore();
        provider = new TestSupport.FakeEntitiesProvider();
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        registry.register(new TestSupport.FakeEnvironmentProvider());
        RefreshService refresh = new RefreshService(registry, store, Runnable::run);
        query = new QueryService(store,
                com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy.defaults(),
                refresh, () -> clock[0]);
    }

    private static ObservationRequest sheepBox() {
        return ObservationRequest.entities("minecraft:overworld", Bounds.of(0, 64, 0, 3, 64, 3),
                "minecraft:sheep", null);
    }

    private TestSupport.Ctx ctx(String world) {
        return new TestSupport.Ctx(world, 42L, clock[0]);
    }

    @Test
    void readNeverScans() {
        ReadResult result = query.read("live", sheepBox());
        assertFalse(result.present());
        assertEquals(Freshness.UNKNOWN, result.freshness());
        assertEquals(0, provider.invocations.get(), "read 绝不能触发采集");
    }

    @Test
    void refreshThenReadIsCurrent() {
        query.refresh(ctx("live"), sheepBox());
        ReadResult result = query.read("live", sheepBox());
        assertTrue(result.present());
        assertEquals(Freshness.CURRENT, result.freshness());
        assertTrue(result.canProveAbsence());
    }

    @Test
    void differentWorldsDoNotShareObservations() {
        query.refresh(ctx("live"), sheepBox());
        assertTrue(query.read("live", sheepBox()).present());
        assertFalse(query.read("other-save", sheepBox()).present());
        assertEquals(1, store.size());
    }

    @Test
    void partialCoverageCannotProveAbsence() {
        provider.next = ProviderResult.of(new EntityCountPayload("minecraft:overworld",
                Bounds.of(0, 64, 0, 3, 64, 3), "minecraft:sheep", null, 1, 10, 3), Coverage.PARTIAL);
        query.refresh(ctx("live"), sheepBox());

        ReadResult result = query.read("live", sheepBox());
        assertEquals(Coverage.PARTIAL, result.coverage());
        assertFalse(result.canProveAbsence());
        assertTrue(result.note().contains("部分范围"));
    }

    @Test
    void completeZeroCanProveAbsence() {
        provider.next = ProviderResult.of(new EntityCountPayload("minecraft:overworld",
                Bounds.of(0, 64, 0, 3, 64, 3), "minecraft:sheep", null, 0, 10, 0), Coverage.COMPLETE);
        query.refresh(ctx("live"), sheepBox());

        ReadResult result = query.read("live", sheepBox());
        assertTrue(result.canProveAbsence());
        assertEquals(0, ((EntityCountPayload) result.observation().payload()).count());
    }

    @Test
    void staleObservationIsNotTrustworthy() {
        query.refresh(ctx("live"), sheepBox());
        clock[0] += 10_000_000L;
        ReadResult result = query.read("live", sheepBox());
        assertEquals(Freshness.STALE, result.freshness());
        assertFalse(result.canProveAbsence());
        assertTrue(result.note().contains("已过期"));
    }
}
