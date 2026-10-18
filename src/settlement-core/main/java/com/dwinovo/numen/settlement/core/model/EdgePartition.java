package com.dwinovo.numen.settlement.core.model;

/** 一条共享边的合并状态。缺省（未登记）= {@link Partition#WALL}。 */
public record EdgePartition(EdgeKey key, Partition partition) {

    public EdgePartition {
        if (key == null) throw new IllegalArgumentException("edge key required");
        if (partition == null) partition = Partition.WALL;
    }

    public static EdgePartition open(CellKey a, CellKey b) {
        return new EdgePartition(EdgeKey.between(a, b), Partition.OPEN);
    }

    public static EdgePartition wall(CellKey a, CellKey b) {
        return new EdgePartition(EdgeKey.between(a, b), Partition.WALL);
    }
}
