package com.dwinovo.numen.worldstate.core.model;

import java.util.Locale;

/**
 * 世界观测的分类。每个分类对应一类明确的数据形状和一个采集器——
 * <b>先有分类和有类型的数据，再有通用入口</b>，不要把所有东西塞进一个无约束的 JSON 大口袋。
 */
public enum Topic {

    /** 维度、时间、天气。范围无关，全局常量级。 */
    ENVIRONMENT("environment"),
    /** 指定点或有界区域的方块状态。 */
    BLOCKS("blocks"),
    /** 范围内的动物、村民等实体（计数/快照）。 */
    ENTITIES("entities"),
    /** 指定容器及物品快照。 */
    CONTAINERS("containers"),
    /** 从基建模块读取的设施摘要适配器（后续接入）。 */
    FACILITIES("facilities");

    private final String id;

    Topic(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Topic byId(String id) {
        if (id == null) return null;
        String v = id.trim().toLowerCase(Locale.ROOT);
        for (Topic t : values()) {
            if (t.id.equals(v)) return t;
        }
        return null;
    }
}
