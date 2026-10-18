package com.dwinovo.numen.settlement.core.model;

/**
 * 保护分区类别。
 *
 * <p><b>作业区故意不在这里</b>：它是"允许合规操作"的元数据，不产生硬禁。
 * 把整片农田锁死的话，AI 不拆家了却也没法收麦子、补种、维修——这正是用户点名的坑。
 */
public enum ZoneKind {
    /** 墙、围栏、地基、床、箱子等结构：禁止随意拆除或替换。 */
    STRUCTURE,
    /** 入口、通道等空间：禁止随意填堵。 */
    SPACE
}
