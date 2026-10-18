package com.dwinovo.numen.settlement.core.accept;

import com.dwinovo.numen.settlement.core.model.BlockBox;

import java.util.List;

/**
 * 验收读取世界的只读接口（纯核心不依赖 Minecraft，把它隔出去）。
 *
 * <p><b>关键契约：区块未加载时不得假装世界是空的</b>。{@link #loaded} 用来显式区分
 * "确认没有"和"看不到"。看不到时上层必须判 UNKNOWN 并保留登记/历史，不能用旧缓存
 * 直接判当前状态。
 */
public interface WorldProbe {

    /** 这一格所在的区块是否加载（能看到真实方块）。 */
    boolean loaded(String dimension, int x, int y, int z);

    /** 该格方块 id；未加载返回 null（别返回 air 冒充确认过的空）。 */
    String blockIdAt(String dimension, int x, int y, int z);

    /** 盒内的实体；未加载时返回空表（配合 {@link #loaded} 区分）。 */
    List<EntityView> entities(String dimension, BlockBox box);
}
