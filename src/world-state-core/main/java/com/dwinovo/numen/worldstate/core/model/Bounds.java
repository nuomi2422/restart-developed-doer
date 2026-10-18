package com.dwinovo.numen.worldstate.core.model;

/**
 * 轴对齐的闭区间方块盒（含两端）。世界观测自己的范围表示——不依赖 settlement-core，
 * 保持本模块可独立编译与单测。
 */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Bounds {
        if (minX > maxX) { int t = minX; minX = maxX; maxX = t; }
        if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }
        if (minZ > maxZ) { int t = minZ; minZ = maxZ; maxZ = t; }
    }

    public static Bounds of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Bounds(x1, y1, z1, x2, y2, z2);
    }

    public static Bounds at(int x, int y, int z) {
        return new Bounds(x, y, z, x, y, z);
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean containsColumn(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public Bounds expand(int dx, int dy, int dz) {
        return new Bounds(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** 稳定的规范串，用作观测键的一部分（与展示无关，只求同一范围必定同串）。 */
    public String canonical() {
        return minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ;
    }

    public static Bounds parse(String canonical) {
        if (canonical == null || canonical.isBlank()) return null;
        try {
            String[] halves = canonical.split("\\.\\.");
            if (halves.length != 2) return null;
            int[] a = parseTriple(halves[0]);
            int[] b = parseTriple(halves[1]);
            if (a == null || b == null) return null;
            return new Bounds(a[0], a[1], a[2], b[0], b[1], b[2]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int[] parseTriple(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        return new int[]{Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
    }
}
