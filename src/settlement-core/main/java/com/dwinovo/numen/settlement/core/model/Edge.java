package com.dwinovo.numen.settlement.core.model;

/**
 * 网格单元的朝向（用于找邻居、定位共享边）。
 *
 * <p>注意：{@link Edge#NORTH}/{@link Edge#WEST} 只用来<b>寻找邻居</b>；
 * 存储共享边时一律归一到 EAST/SOUTH（见 {@link EdgeKey}），否则一条共享边会有两个键，
 * 拆墙时改一个漏一个。
 */
public enum Edge {
    NORTH, EAST, SOUTH, WEST;

    public CellKey neighborOf(CellKey c) {
        return switch (this) {
            case NORTH -> new CellKey(c.cx(), c.cz() - 1);
            case SOUTH -> new CellKey(c.cx(), c.cz() + 1);
            case EAST -> new CellKey(c.cx() + 1, c.cz());
            case WEST -> new CellKey(c.cx() - 1, c.cz());
        };
    }

    public Edge opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }
}
