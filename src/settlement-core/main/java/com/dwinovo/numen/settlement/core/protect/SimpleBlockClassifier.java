package com.dwinovo.numen.settlement.core.protect;

import java.util.Set;

/**
 * 仅供测试/兜底的极简分类器：命中一小撮"明显不挡路"的方块 id 就放行，其余一律当成挡路。
 *
 * <p><b>生产实现应收敛到真实碰撞箱</b>（见 {@link BlockClassifier}）。这里刻意保守：
 * 认不出来的一律按挡路处理，宁可拒绝"往通道里放个不认识的装饰块"，也不放过堵门。
 */
public final class SimpleBlockClassifier implements BlockClassifier {

    private static final Set<String> NON_OBSTRUCTING = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:water", "minecraft:lava",
            "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_torch",
            "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
            "minecraft:lantern", "minecraft:soul_lantern",
            "minecraft:short_grass", "minecraft:grass", "minecraft:tall_grass",
            "minecraft:fern", "minecraft:large_fern",
            "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
            "minecraft:allium", "minecraft:azure_bluet", "minecraft:cornflower",
            "minecraft:lily_of_the_valley", "minecraft:oxeye_daisy",
            "minecraft:red_mushroom", "minecraft:brown_mushroom",
            "minecraft:rail", "minecraft:powered_rail", "minecraft:detector_rail",
            "minecraft:activator_rail", "minecraft:string", "minecraft:vine",
            "minecraft:snow", "minecraft:carpet");

    @Override
    public boolean obstructs(String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        return !NON_OBSTRUCTING.contains(blockId);
    }
}
