package com.dwinovo.numen.worldstate.core.request;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.Locale;

/**
 * 一次「去取」的请求：主题 + 维度 + 目标/范围 + 过滤条件。
 *
 * <p>{@code target} 与 {@code filter} 是键的规范串；{@code bounds} 是给采集器直接用的结构化范围
 * （环境的 {@code target/filter} 都为空，{@code bounds} 为 null）。
 */
public record ObservationRequest(Topic topic, String dimension, String target, String filter, Bounds bounds) {

    public ObservationRequest {
        if (topic == null) throw new IllegalArgumentException("topic required");
        dimension = dimension == null ? "" : dimension.toLowerCase(Locale.ROOT).trim();
        target = target == null ? "" : target.trim();
        filter = filter == null ? "" : filter.trim();
    }

    /** 环境快照请求：一个维度一份。 */
    public static ObservationRequest environment(String dimension) {
        return new ObservationRequest(Topic.ENVIRONMENT, dimension, "", "", null);
    }

    /** 实体计数请求：范围内的某类实体，或某类别（passive/all）。 */
    public static ObservationRequest entities(String dimension, Bounds box, String entityType, String category) {
        if (box == null) throw new IllegalArgumentException("bounds required for entities");
        String type = entityType == null || entityType.isBlank() ? null : entityType.trim();
        String cat = type != null ? null : (category == null || category.isBlank() ? "all" : category.trim());
        String filter = type != null ? "type=" + type : "category=" + cat;
        return new ObservationRequest(Topic.ENTITIES, dimension, "box=" + box.canonical(), filter, box);
    }

    /** 方块状态请求：范围内的方块（可选只匹配某方块 id / tag）。 */
    public static ObservationRequest blocks(String dimension, Bounds box, String blockFilter) {
        if (box == null) throw new IllegalArgumentException("bounds required for blocks");
        String f = blockFilter == null || blockFilter.isBlank() ? "" : "block=" + blockFilter.trim();
        return new ObservationRequest(Topic.BLOCKS, dimension, "box=" + box.canonical(), f, box);
    }

    /** 该请求在世界存档 {@code worldId} 下的观测键。 */
    public ObservationKey keyFor(String worldId) {
        return new ObservationKey(worldId, dimension, topic, target, filter);
    }

    public long volume() {
        return bounds == null ? 0L : bounds.volume();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(topic.id());
        if (!dimension.isEmpty()) sb.append(" @ ").append(dimension);
        if (!target.isEmpty()) sb.append(' ').append(target);
        if (!filter.isEmpty()) sb.append(" [").append(filter).append(']');
        return sb.toString();
    }
}
