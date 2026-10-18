package com.dwinovo.numen.settlement.core.model;

import java.util.List;

/**
 * 模块化基地平面（用户的核心诉求）：一块正方形平地，按固定边长切网格，
 * 每格登记一种设施；相邻格默认有隔墙，合并时把中间那道墙去掉。
 *
 * <p>默认 {@code cellsX=cellsZ=5, cellSize=5, gap=0} → 25×25 平面、25 个 5×5 单元，格与格共边。
 * 尺寸全部参数化：{@code gap>0} 时格与格之间留 {@code gap} 列/行作边界（"中间留一格当边界"），
 * 此时平台边长 = {@code cells*cellSize + (cells-1)*gap}。不要把某个具体数字写死。
 *
 * <p>{@link #assignment} 与 {@link #edges} 用 List 而非 Map 存，是为了让 Gson 反序列化
 * 不需要自定义 Map key 适配器。
 */
public record PlatformPlan(
        String platformId,
        String dimension,
        int originX,
        int floorY,
        int originZ,
        int cellsX,
        int cellsZ,
        int cellSize,
        int gap,
        int wallHeight,
        List<CellAssignment> assignment,
        List<EdgePartition> edges) {

    public PlatformPlan {
        if (platformId == null || platformId.isBlank()) throw new IllegalArgumentException("platform id required");
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("platform dimension required");
        if (cellsX < 1 || cellsZ < 1) throw new IllegalArgumentException("grid must be at least 1x1");
        if (cellSize < 1) throw new IllegalArgumentException("cell size must be >= 1");
        if (gap < 0) throw new IllegalArgumentException("gap must be >= 0");
        if (wallHeight < 1) throw new IllegalArgumentException("wall height must be >= 1");
        assignment = assignment == null ? List.of() : List.copyOf(assignment);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** 5×5 网格 / 5 格边长 / 无间隙的 25×25 默认平面。 */
    public static PlatformPlan square25(String platformId, String dimension, int originX, int floorY, int originZ) {
        return new PlatformPlan(platformId, dimension, originX, floorY, originZ,
                5, 5, 5, 0, 4, List.of(), List.of());
    }

    public int stride() {
        return cellSize + gap;
    }

    /** 平台沿 X 的边长（方块数）。 */
    public int sizeX() {
        return cellsX * cellSize + (cellsX - 1) * gap;
    }

    public int sizeZ() {
        return cellsZ * cellSize + (cellsZ - 1) * gap;
    }

    public int maxX() {
        return originX + sizeX() - 1;
    }

    public int maxZ() {
        return originZ + sizeZ() - 1;
    }

    public boolean contains(CellKey c) {
        return c != null && c.cx() >= 0 && c.cx() < cellsX && c.cz() >= 0 && c.cz() < cellsZ;
    }

    /** 该格登记给哪个设施；空格返回 null。 */
    public String facilityAt(CellKey c) {
        for (CellAssignment a : assignment) {
            if (a.cell().equals(c)) return a.facilityId();
        }
        return null;
    }

    /** 两个相邻格之间的隔墙状态；不相邻或未登记时按 WALL 处理。 */
    public Partition partitionOf(CellKey a, CellKey b) {
        EdgeKey key = EdgeKey.between(a, b);
        if (key == null) return Partition.WALL;
        for (EdgePartition e : edges) {
            if (e.key().equals(key)) return e.partition();
        }
        return Partition.WALL;
    }

    public PlatformPlan withAssignment(CellKey cell, String facilityId) {
        List<CellAssignment> next = new java.util.ArrayList<>();
        boolean replaced = false;
        for (CellAssignment a : assignment) {
            if (a.cell().equals(cell)) { next.add(CellAssignment.of(cell, facilityId)); replaced = true; }
            else next.add(a);
        }
        if (!replaced) next.add(CellAssignment.of(cell, facilityId));
        return new PlatformPlan(platformId, dimension, originX, floorY, originZ, cellsX, cellsZ,
                cellSize, gap, wallHeight, next, edges);
    }

    public PlatformPlan withPartition(CellKey a, CellKey b, Partition partition) {
        EdgeKey key = EdgeKey.between(a, b);
        if (key == null) throw new IllegalArgumentException("cells are not adjacent: " + a + "," + b);
        List<EdgePartition> next = new java.util.ArrayList<>();
        boolean replaced = false;
        for (EdgePartition e : edges) {
            if (e.key().equals(key)) { next.add(new EdgePartition(key, partition)); replaced = true; }
            else next.add(e);
        }
        if (!replaced) next.add(new EdgePartition(key, partition));
        return new PlatformPlan(platformId, dimension, originX, floorY, originZ, cellsX, cellsZ,
                cellSize, gap, wallHeight, assignment, next);
    }
}
