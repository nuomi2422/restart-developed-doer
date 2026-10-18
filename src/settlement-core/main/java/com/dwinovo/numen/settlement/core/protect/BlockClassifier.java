package com.dwinovo.numen.settlement.core.protect;

/**
 * 方块"是否挡路"的判据。纯核心不依赖 Minecraft，所以用接口把它隔出去。
 *
 * <p>宿主侧应实现为"该方块碰撞箱是否为空"（{@code getCollisionShape().isEmpty()}）——
 * 这比列一张方块清单可靠得多。本模块自带的
 * {@link SimpleBlockClassifier} 只够单元测试用，别拿它当完整判据。
 */
public interface BlockClassifier {

    /** 这个方块放下去会挡住通道吗（实心、有碰撞、人或动物过不去）。 */
    boolean obstructs(String blockId);
}
