package com.dwinovo.numen.settlement.core.protect;

import com.dwinovo.numen.settlement.core.model.BlockBox;

/**
 * 临时施工/维修授权：让某个设施在<b>自己当前作业范围</b>内可以突破自身保护规则。
 *
 * <p>只授"当前设施、当前作业范围"的权；<b>随任务生命周期失效</b>——暂停、取消、结束后
 * 授权不再存在，持久保护继续生效。{@code expiresAtEpochMs<=0} 表示不设过期（由任务生命周期
 * 显式撤销）。
 */
public record Grant(String facilityId, String dimension, BlockBox box, long expiresAtEpochMs) {

    public Grant {
        if (facilityId == null || facilityId.isBlank()) throw new IllegalArgumentException("grant facilityId required");
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("grant dimension required");
        if (box == null) throw new IllegalArgumentException("grant box required");
    }

    public static Grant of(String facilityId, String dimension, BlockBox box) {
        return new Grant(facilityId, dimension, box, 0L);
    }

    public static Grant expiring(String facilityId, String dimension, BlockBox box, long expiresAtEpochMs) {
        return new Grant(facilityId, dimension, box, expiresAtEpochMs);
    }

    public boolean covers(String dim, int x, int y, int z, long nowEpochMs) {
        if (dim == null || !dim.equals(dimension)) return false;
        if (expiresAtEpochMs > 0 && nowEpochMs > expiresAtEpochMs) return false;
        return box.contains(x, y, z);
    }
}
