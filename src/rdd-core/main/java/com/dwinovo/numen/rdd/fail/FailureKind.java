package com.dwinovo.numen.rdd.fail;

/**
 * 失败类型（P2.0 硬分类输入）。刻意保守、有限枚举：新增类型要显式加，
 * 不给"未知"以外的模糊空间——未知就保持 {@link #UNKNOWN}，不许猜成"缺工具"。
 */
public enum FailureKind {
    /** 资源不存在/耗尽（缺材料、目标区域没有该矿）。 */
    RESOURCE_MISSING,
    /** 路径不可达（基岩/流体/深埋阻挡）。 */
    PATH_BLOCKED,
    /** 工具执行失败/参数形状不对（可修调用后重试）。 */
    TOOL_ERROR,
    /** 同伴死亡（掉装备/中断执行）。 */
    DEATH,
    /** 目标已永久失去（基地被毁、入口丢失、关键资源不可获得）。 */
    TARGET_LOST,
    /** 确定是程序缺陷（稳定复现、已排除上面几类）。 */
    SOFTWARE_DEFECT,
    /** 未知（无证据时保持未知）。 */
    UNKNOWN
}
