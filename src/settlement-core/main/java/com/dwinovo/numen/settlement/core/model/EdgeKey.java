package com.dwinovo.numen.settlement.core.model;

/**
 * 一条共享边的<b>规范键</b>：只存"低号格 + EAST/SOUTH"。
 *
 * <p>相邻的两个单元 (0,0) 与 (1,0) 之间那条边，既可以说"(0,0) 的东边"，也可以说
 * "(1,0) 的西边"。若不归一，同一堵墙有两个键，拆墙只改一个，另一侧仍记着 WALL ——
 * 表现为"合并了但没完全合并"。归一化规则：(cx,cz) 的 EAST 等价于 (cx+1,cz) 的 WEST，
 * 取字典序小的一侧、正方向那面墙。
 */
public record EdgeKey(CellKey low, Edge side) {

    public EdgeKey {
        if (low == null) throw new IllegalArgumentException("low cell required");
        if (side != Edge.EAST && side != Edge.SOUTH) {
            throw new IllegalArgumentException("edge key side must be EAST or SOUTH, got " + side);
        }
    }

    /** 由两个相邻单元求出规范键；不相邻返回 null。 */
    public static EdgeKey between(CellKey a, CellKey b) {
        if (a == null || b == null) return null;
        if (a.cx() + 1 == b.cx() && a.cz() == b.cz()) return new EdgeKey(a, Edge.EAST);
        if (b.cx() + 1 == a.cx() && a.cz() == b.cz()) return new EdgeKey(b, Edge.EAST);
        if (a.cz() + 1 == b.cz() && a.cx() == b.cx()) return new EdgeKey(a, Edge.SOUTH);
        if (b.cz() + 1 == a.cz() && a.cx() == b.cx()) return new EdgeKey(b, Edge.SOUTH);
        return null;
    }

    public CellKey high() {
        return side.neighborOf(low);
    }
}
