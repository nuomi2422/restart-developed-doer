package com.dwinovo.numen.worldstate.core.model;

/** 观测的来源类型：直接观测世界，还是别的模块提供的摘要。 */
public enum Origin {
    /** 采集器直接读世界得到的。 */
    DIRECT,
    /** 由其它模块（如基建）提供、世界观测只做适配缓存。 */
    MODULE
}
