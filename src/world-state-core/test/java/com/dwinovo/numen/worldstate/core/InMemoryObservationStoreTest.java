package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryObservationStoreTest {

    private static EntityCountPayload payload(int count) {
        return new EntityCountPayload("minecraft:overworld", Bounds.at(0, 64, 0),
                "minecraft:sheep", null, count, 1, 0);
    }

    private static Observation obs(String world, Bounds box, int count, long revision) {
        ObservationKey key = TestSupport.entityKey(world, "minecraft:overworld", box, "minecraft:sheep");
        return new Observation(key, payload(count), "test", com.dwinovo.numen.worldstate.core.model.Origin.DIRECT,
                0L, 1000L, Coverage.COMPLETE, Freshness.CURRENT, revision, 1);
    }

    @Test
    void putThenGet() {
        InMemoryObservationStore store = new InMemoryObservationStore();
        Observation o = obs("live", Bounds.at(0, 64, 0), 2, 1);
        assertTrue(store.put(o));
        assertEquals(o, store.get(o.key()).orElseThrow());
        assertEquals(1, store.size());
    }

    @Test
    void newerRevisionWinsAndStaleRevisionRejected() {
        InMemoryObservationStore store = new InMemoryObservationStore();
        Bounds box = Bounds.at(0, 64, 0);
        assertTrue(store.put(obs("live", box, 1, 1)));
        assertTrue(store.put(obs("live", box, 5, 2)));
        assertEquals(5, countOf(store, box));
        // 迟到的旧刷新不得覆盖
        assertFalse(store.put(obs("live", box, 99, 1)));
        assertEquals(5, countOf(store, box));
    }

    @Test
    void lruEvictsLeastRecentlyUsed() {
        InMemoryObservationStore store = new InMemoryObservationStore(2);
        Bounds b1 = Bounds.at(0, 64, 0);
        Bounds b2 = Bounds.at(10, 64, 10);
        Bounds b3 = Bounds.at(20, 64, 20);
        store.put(obs("live", b1, 1, 1));
        store.put(obs("live", b2, 1, 1));
        store.get(TestSupport.entityKey("live", "minecraft:overworld", b1, "minecraft:sheep")); // touch b1
        store.put(obs("live", b3, 1, 1));
        assertEquals(2, store.size());
        assertTrue(store.get(TestSupport.entityKey("live", "minecraft:overworld", b1,
                "minecraft:sheep")).isPresent());
        assertFalse(store.get(TestSupport.entityKey("live", "minecraft:overworld", b2,
                "minecraft:sheep")).isPresent());
        assertTrue(store.get(TestSupport.entityKey("live", "minecraft:overworld", b3,
                "minecraft:sheep")).isPresent());
    }

    @Test
    void allReturnsCopy() {
        InMemoryObservationStore store = new InMemoryObservationStore();
        store.put(obs("live", Bounds.at(0, 64, 0), 1, 1));
        List<Observation> snapshot = store.all();
        snapshot.clear();
        assertEquals(1, store.size());
    }

    private static int countOf(InMemoryObservationStore store, Bounds box) {
        Observation o = store.get(TestSupport.entityKey("live", "minecraft:overworld", box,
                "minecraft:sheep")).orElseThrow();
        return ((EntityCountPayload) o.payload()).count();
    }
}
