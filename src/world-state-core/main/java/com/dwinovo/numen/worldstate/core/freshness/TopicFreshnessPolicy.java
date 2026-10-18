package com.dwinovo.numen.worldstate.core.freshness;

import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;

import java.util.EnumMap;
import java.util.Map;

/**
 * 按主题 TTL 判定新鲜度。天气、动物位置、箱子内容变化速度不同，<b>不能全用同一个时间</b>，
 * 所以 TTL 按主题配置。
 *
 * <p>{@link Freshness#HISTORICAL}（重启后载入的动态观测）优先于年龄判定——即使时间戳还在 TTL 内，
 * 它也是上个进程看到的，不能冒充本轮实时结果。
 */
public final class TopicFreshnessPolicy implements FreshnessPolicy {

    private final Map<Topic, Long> ttlMillis = new EnumMap<>(Topic.class);

    public TopicFreshnessPolicy() {
    }

    public static TopicFreshnessPolicy defaults() {
        TopicFreshnessPolicy p = new TopicFreshnessPolicy();
        p.withTtl(Topic.ENVIRONMENT, 30_000L);
        p.withTtl(Topic.ENTITIES, 30_000L);
        p.withTtl(Topic.BLOCKS, 60_000L);
        p.withTtl(Topic.CONTAINERS, 120_000L);
        p.withTtl(Topic.FACILITIES, 120_000L);
        return p;
    }

    public TopicFreshnessPolicy withTtl(Topic topic, long millis) {
        ttlMillis.put(topic, millis);
        return this;
    }

    public long ttlOf(Topic topic) {
        return ttlMillis.getOrDefault(topic, 60_000L);
    }

    @Override
    public Freshness classify(Observation observation, long nowMs) {
        if (observation == null) return Freshness.UNKNOWN;
        if (observation.freshness() == Freshness.HISTORICAL) return Freshness.HISTORICAL;
        long age = observation.ageMillis(nowMs);
        return age <= ttlOf(observation.key().topic()) ? Freshness.CURRENT : Freshness.STALE;
    }
}
