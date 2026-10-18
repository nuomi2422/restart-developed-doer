package com.dwinovo.numen.settlement.core.logic;

/**
 * 蓝图局部坐标 ↔ 旋转后坐标（纯函数，无 Minecraft 类型）。
 *
 * <p>约定与 {@code blueprint action=build} 的 {@code rotation} 一致：<b>顺时针</b>四分之一圈，
 * {@code quarters} 归一到 0..3。绕盒子中心旋转，非正方形盒子旋转后长宽互换。
 *
 * <p>为什么单独收一个类：入口/内部站位/外部站位三个点必须用<b>同一套</b>变换，
 * 否则"门转过去了、门外站位没转"，放置完才发现入口朝向错误——而那时蓝图已经落地。
 */
public final class RotationMath {

    private RotationMath() {}

    /** 二维整数点（局部坐标，可为负——门外站位就在蓝图盒子外）。 */
    public record Point2(int x, int z) {}

    public static int normalizeQuarters(int quarters) {
        return Math.floorMod(quarters, 4);
    }

    /** 旋转后的 X 方向尺寸。 */
    public static int rotatedSizeX(int sizeX, int sizeZ, int quarters) {
        return normalizeQuarters(quarters) % 2 == 0 ? sizeX : sizeZ;
    }

    /** 旋转后的 Z 方向尺寸。 */
    public static int rotatedSizeZ(int sizeX, int sizeZ, int quarters) {
        return normalizeQuarters(quarters) % 2 == 0 ? sizeZ : sizeX;
    }

    /**
     * 把蓝图局部点旋转到"旋转后蓝图"的局部坐标系。
     *
     * <p>盒子局部坐标范围 {@code x∈[0,sizeX), z∈[0,sizeZ)}；门外的点允许越界（如 z=-1）。
     * 顺时针 90°：{@code (x,z) → (sizeZ-1-z, x)}，尺寸变 {@code (sizeZ,sizeX)}。
     */
    public static Point2 rotateLocal(int lx, int lz, int sizeX, int sizeZ, int quarters) {
        return switch (normalizeQuarters(quarters)) {
            case 1 -> new Point2(sizeZ - 1 - lz, lx);
            case 2 -> new Point2(sizeX - 1 - lx, sizeZ - 1 - lz);
            case 3 -> new Point2(lz, sizeX - 1 - lx);
            default -> new Point2(lx, lz);
        };
    }
}
