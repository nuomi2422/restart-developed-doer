package com.dwinovo.numen.settlement.core.model;

/**
 * 轴对齐的闭区间方块盒（含两端）。
 *
 * <p>设施登记、保护范围、作业区、网格单元全部用它表示。用<b>闭区间</b>而不是半开，
 * 是因为它描述的是"哪些方块属于这里"，方块坐标本身就是整数点；端点写成 {@code +size-1}
 * 而不是 {@code +size} 是这块最容易算错的地方（见 {@link com.dwinovo.numen.settlement.core.logic.PlatformMath}）。
 *
 * <p>构造时若两端反了就<b>归一化</b>，不抛异常：调用方从世界坐标取两个角时谁大谁小不定，
 * 让这里统一收口比每个调用点各判一次可靠。
 */
public record BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public BlockBox {
        if (minX > maxX) { int t = minX; minX = maxX; maxX = t; }
        if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }
        if (minZ > maxZ) { int t = minZ; minZ = maxZ; maxZ = t; }
    }

    public static BlockBox of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new BlockBox(x1, y1, z1, x2, y2, z2);
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** 只比水平面（X/Z）——"这一列在不在设施占地里"用。 */
    public boolean containsColumn(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects(BlockBox other) {
        return other != null
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public BlockBox expand(int dx, int dy, int dz) {
        return new BlockBox(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }
}
