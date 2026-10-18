package com.dwinovo.numen.settlement.core.model;

/**
 * 带回维度锚点：入口内外站位、交互点、箱子坐标都用它。
 *
 * <p>必须带维度——同一个 {@code BlockPos} 在主世界和下界可以完全重合，
 * 只存坐标会在跨维度时静默指错地方（这是 RDD 床锚点踩过的同一个坑）。
 */
public record DimAnchor(String dimension, int x, int y, int z) {

    public static DimAnchor of(String dimension, int x, int y, int z) {
        return new DimAnchor(dimension, x, y, z);
    }

    public boolean inDimension(String dim) {
        return dimension != null && dimension.equals(dim);
    }
}
