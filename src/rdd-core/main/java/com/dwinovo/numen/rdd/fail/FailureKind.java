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
    /**
     * 不是失败，而是<b>执行层主动协商</b>：士兵在干活途中认为当前二级方向不对，
     * 上报了 COUNTER / REJECT。
     *
     * <p><b>为什么必须与 UNKNOWN 分开</b>（2026-09-29）：重规划入口曾对所有情况
     * 硬编码 {@code UNKNOWN}，于是协商场景也套上了 {@code attempt=1} 分支，
     * 给规划器注入「上一次生成的子步骤被判定不可执行」这句<b>假话</b>——
     * 上一版其实完全可执行，士兵只是不喜欢。这句假话还会经 knownFailures
     * 进入经验召回查询串，把召回方向带偏到「改 asset_key 形状」类经验。
     */
    NEGOTIATION,
    /** 未知（无证据时保持未知）。 */
    UNKNOWN
}
