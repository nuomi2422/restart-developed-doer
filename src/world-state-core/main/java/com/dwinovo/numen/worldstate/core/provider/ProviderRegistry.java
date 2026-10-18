package com.dwinovo.numen.worldstate.core.provider;

import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 主题 → 采集器 的注册表。查询服务只认这一张表，不需要知道底下谁在干活。
 *
 * <p>未接入的主题（如 {@code containers} / {@code facilities}）查不到采集器 →
 * 上层明确返回 {@code NO_PROVIDER}，而不是假装成功。
 */
public final class ProviderRegistry {

    private final Map<Topic, WorldProvider> byTopic = new EnumMap<>(Topic.class);

    public void register(WorldProvider provider) {
        if (provider == null || provider.topic() == null) {
            throw new IllegalArgumentException("provider and topic required");
        }
        byTopic.put(provider.topic(), provider);
    }

    public Optional<WorldProvider> byTopic(Topic topic) {
        return Optional.ofNullable(topic == null ? null : byTopic.get(topic));
    }

    /** 找到能服务该请求的采集器（主题匹配且 {@link WorldProvider#supports} 通过）。 */
    public Optional<WorldProvider> resolve(ObservationRequest request) {
        if (request == null) return Optional.empty();
        WorldProvider provider = byTopic.get(request.topic());
        return provider != null && provider.supports(request) ? Optional.of(provider) : Optional.empty();
    }

    public Set<Topic> topics() {
        return Set.copyOf(byTopic.keySet());
    }
}
