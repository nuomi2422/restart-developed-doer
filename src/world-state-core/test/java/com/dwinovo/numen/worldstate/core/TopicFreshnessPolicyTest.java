package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicFreshnessPolicyTest {

    private static Observation obs(long epochMs, Freshness freshness) {
        ObservationKey key = TestSupport.entityKey("live", "minecraft:overworld",
                Bounds.at(0, 64, 0), "minecraft:sheep");
        return new Observation(key, new EntityCountPayload("minecraft:overworld", Bounds.at(0, 64, 0),
                "minecraft:sheep", null, 1, 1, 0), "test",
                com.dwinovo.numen.worldstate.core.model.Origin.DIRECT, 0L, epochMs,
                Coverage.COMPLETE, freshness, 1, 1);
    }

    @Test
    void nullIsUnknown() {
        assertEquals(Freshness.UNKNOWN, TopicFreshnessPolicy.defaults().classify(null, 0L));
    }

    @Test
    void withinTtlIsCurrent() {
        TopicFreshnessPolicy policy = TopicFreshnessPolicy.defaults();
        long ttl = policy.ttlOf(Topic.ENTITIES);
        assertEquals(Freshness.CURRENT, policy.classify(obs(1_000L, Freshness.CURRENT), 1_000L + ttl));
    }

    @Test
    void beyondTtlIsStale() {
        TopicFreshnessPolicy policy = TopicFreshnessPolicy.defaults();
        long ttl = policy.ttlOf(Topic.ENTITIES);
        assertEquals(Freshness.STALE, policy.classify(obs(1_000L, Freshness.CURRENT), 1_000L + ttl + 1));
    }

    @Test
    void historicalStaysHistoricalEvenWhenFresh() {
        TopicFreshnessPolicy policy = TopicFreshnessPolicy.defaults();
        assertEquals(Freshness.HISTORICAL, policy.classify(obs(1_000L, Freshness.HISTORICAL), 1_000L));
    }
}
