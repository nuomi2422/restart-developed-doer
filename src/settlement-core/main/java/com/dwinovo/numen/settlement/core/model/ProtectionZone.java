package com.dwinovo.numen.settlement.core.model;

/** 一块受保护的区域：类别 + 范围。落在区域内的改动按 {@link ZoneKind} 的规则裁决。 */
public record ProtectionZone(ZoneKind kind, BlockBox box) {

    public ProtectionZone {
        if (kind == null) throw new IllegalArgumentException("zone kind required");
        if (box == null) throw new IllegalArgumentException("zone box required");
    }

    public static ProtectionZone structure(BlockBox box) {
        return new ProtectionZone(ZoneKind.STRUCTURE, box);
    }

    public static ProtectionZone space(BlockBox box) {
        return new ProtectionZone(ZoneKind.SPACE, box);
    }
}
