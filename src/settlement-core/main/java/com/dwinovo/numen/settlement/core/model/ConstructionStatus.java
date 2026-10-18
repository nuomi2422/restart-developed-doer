package com.dwinovo.numen.settlement.core.model;

/**
 * 施工状态（第一层判据的<b>生命周期</b>部分，与 {@link ConstructionProgress} 的计数互补）。
 *
 * <p>为什么单独立一个枚举而不是复用 {@link Verdict}：两者回答的不是同一个问题。
 * {@code Verdict} 答"现在这个结构能不能用"（由世界探针现场判），
 * 本枚举答"我们这边<b>这件事</b>进行到哪了"（由工具自己记账）。
 * 半成品在世界上是"结构不可用"，但在账本上必须是"施工中"——
 * 混成一个字段就会出现"中途停止后地图把半成品显示成空地"的老问题。
 *
 * <p>核心诉求：<b>开工前就登记为施工中</b>。否则中途停止或重启后，
 * 地图把半成品当空地，AI 会在同一位置创建第二座设施。
 */
public enum ConstructionStatus {
    /** 未走放置流程（{@code settlement_register} 手工登记的历史数据，没有施工账）。 */
    UNTRACKED,
    /** 已预留地块，尚未动土。 */
    PLANNED,
    /** 施工中（已开工；中途停止/重启也停在这个状态）。 */
    BUILDING,
    /** 缺料或受阻，等补料/排除阻挡后续建。 */
    BLOCKED,
    /** 施工收口（图纸格都交代过）。是否"结构可用/能生产"另由验收判。 */
    COMPLETE,
    /** 施工失败（工具报错、超时、被替换）。 */
    FAILED;

    /** 是否还占着"这块地正在建什么"的账（未收口）。 */
    public boolean isOpen() {
        return this == PLANNED || this == BUILDING || this == BLOCKED;
    }

    /** 是否需要一个施工授权（只有真要动土的阶段才需要）。 */
    public boolean needsGrant() {
        return this == PLANNED || this == BUILDING || this == BLOCKED;
    }
}
