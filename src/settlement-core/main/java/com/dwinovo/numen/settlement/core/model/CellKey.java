package com.dwinovo.numen.settlement.core.model;

/**
 * 模块化平面上的一个网格单元坐标（列号/行号，从 0 起）。
 *
 * <p>它是<b>网格身份</b>，不是世界坐标。世界坐标由
 * {@link com.dwinovo.numen.settlement.core.logic.PlatformMath} 按平台的 origin/尺寸换算。
 */
public record CellKey(int cx, int cz) implements Comparable<CellKey> {

    public CellKey {
        if (cx < 0 || cz < 0) throw new IllegalArgumentException("cell index must be >= 0: " + cx + "," + cz);
    }

    public static CellKey of(int cx, int cz) {
        return new CellKey(cx, cz);
    }

    @Override
    public int compareTo(CellKey o) {
        int c = Integer.compare(cx, o.cx);
        return c != 0 ? c : Integer.compare(cz, o.cz);
    }

    public long asLong() {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public static CellKey fromLong(long key) {
        return new CellKey((int) (key >> 32), (int) key);
    }
}
