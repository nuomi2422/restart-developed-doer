package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Topic;

import java.util.Locale;

/**
 * 观测键的完整命名空间：<b>世界存档 + 维度 + 主题 + 目标 + 过滤条件</b>。
 *
 * <p>世界存档必须进键——两个存档在同一维度、同一坐标上不能互相串数据。
 * 各分段归一化为空串而非 null，保证 {@link #canonical()} 是纯净、可解析的单行串。
 *
 * @param worldId   世界存档标识（如存档名 / 世界 id）
 * @param dimension 维度 id，如 {@code minecraft:overworld}
 * @param topic     观测分类
 * @param target    目标/范围的规范串（如方块盒 {@code x1,y1,z1..x2,y2,z2}；环境快照为空）
 * @param filter    过滤条件规范串（如 {@code type=minecraft:sheep}；环境快照为空）
 */
public record ObservationKey(String worldId, String dimension, Topic topic, String target, String filter) {

    private static final String SEP = "|";

    public ObservationKey {
        worldId = nz(worldId);
        dimension = nz(dimension);
        target = nz(target);
        filter = nz(filter);
        if (topic == null) throw new IllegalArgumentException("topic required");
    }

    public static ObservationKey of(String worldId, String dimension, Topic topic, String target, String filter) {
        return new ObservationKey(worldId, dimension, topic, target, filter);
    }

    /** 稳定的单行规范串（键相等 ⟺ 规范串相等）。 */
    public String canonical() {
        return String.join(SEP, worldId, dimension, topic.id(), target, filter);
    }

    /** 解析 {@link #canonical()}；格式不合返回 null。 */
    public static ObservationKey parse(String canonical) {
        if (canonical == null) return null;
        String[] parts = canonical.split("\\" + SEP, 5);
        if (parts.length != 5) return null;
        Topic topic = Topic.byId(parts[2]);
        if (topic == null) return null;
        return new ObservationKey(parts[0], parts[1], topic, parts[3], parts[4]);
    }

    private static String nz(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
