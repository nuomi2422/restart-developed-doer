package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderException;
import com.dwinovo.numen.worldstate.core.provider.ProviderResult;
import com.dwinovo.numen.worldstate.core.provider.WorldProvider;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;

import java.util.concurrent.atomic.AtomicInteger;

/** 单测共享的假对象：上下文、采集器、观测构造器。 */
final class TestSupport {

    private TestSupport() {
    }

    static final class Ctx implements CollectionContext {
        private final String worldId;
        private final long tick;
        private final long now;

        Ctx(String worldId, long tick, long now) {
            this.worldId = worldId;
            this.tick = tick;
            this.now = now;
        }

        @Override public String worldId() { return worldId; }
        @Override public long gameTick() { return tick; }
        @Override public long nowMs() { return now; }
    }

    /** 可编程的实体采集器：可返回成功、抛明确失败、抛内部异常或阻塞。 */
    static final class FakeEntitiesProvider implements WorldProvider {
        final AtomicInteger invocations = new AtomicInteger();
        volatile ProviderResult next = ProviderResult.of(
                new EntityCountPayload("minecraft:overworld", Bounds.at(0, 64, 0),
                        "minecraft:sheep", null, 2, 1, 0), Coverage.COMPLETE);
        volatile RuntimeException throwNext;
        volatile long sleepMillis = 0L;
        volatile long maxVolume = 0L;

        @Override public Topic topic() { return Topic.ENTITIES; }
        @Override public String id() { return "fake-entities"; }
        @Override public int dataVersion() { return 7; }
        @Override public long maxVolume() { return maxVolume; }

        @Override
        public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
            invocations.incrementAndGet();
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwNext != null) throw throwNext;
            return next;
        }
    }

    static final class FakeEnvironmentProvider implements WorldProvider {
        @Override public Topic topic() { return Topic.ENVIRONMENT; }
        @Override public String id() { return "fake-env"; }
        @Override public int dataVersion() { return 1; }

        @Override
        public ProviderResult collect(CollectionContext ctx, ObservationRequest request) {
            return ProviderResult.of(new EnvironmentPayload(request.dimension(), 6000L, true, false),
                    Coverage.COMPLETE);
        }
    }

    static Observation observation(ObservationKey key, ObservationPayload payload, long epochMs,
                                  Coverage coverage, Freshness freshness, long revision) {
        return new Observation(key, payload, "test", Origin.DIRECT, 0L, epochMs, coverage, freshness,
                revision, 1);
    }

    static ObservationKey entityKey(String worldId, String dimension, Bounds box, String type) {
        return ObservationRequest.entities(dimension, box, type, null).keyFor(worldId);
    }

    static ProviderException unloaded() {
        return new ProviderException(com.dwinovo.numen.worldstate.core.model.FailReason.CHUNK_UNLOADED,
                "chunk not loaded");
    }
}
