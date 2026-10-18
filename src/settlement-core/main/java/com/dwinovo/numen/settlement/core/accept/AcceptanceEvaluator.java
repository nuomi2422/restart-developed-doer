package com.dwinovo.numen.settlement.core.accept;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.Verdict;

import java.util.List;

/**
 * 三层判据的第 2、3 层：<b>结构可用</b> 与 <b>生产条件</b>。
 * （第 1 层"施工进度"是 {@link FacilityRecord#construction()} 的对账，不在这里重复。）
 *
 * <p>分开的意义：建造工具执行结束 ≠ 设施已建好 ≠ 已投入生产。空羊圈可以判结构
 * {@link Verdict#USABLE}，但生产只能是 {@link ProductionState#NOT_READY}——"已建好"绝不能
 * 被当成"已有稳定食物来源"。
 *
 * <p>区块未加载一律返回 {@link Verdict#UNKNOWN}/{@link ProductionState#UNKNOWN}，不用旧缓存
 * 冒充当前事实。
 */
public final class AcceptanceEvaluator {

    /** 牧场的动物阈值：至少一只。任务链要"至少两只"是另一层的判据，不是设施生产条件。 */
    private static final int MIN_ANIMALS = 1;
    private static final int MIN_CROPS = 1;

    private static final List<String> CROP_TOKENS = List.of(
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "sweet_berry");

    private AcceptanceEvaluator() {}

    /** 第 2 层：结构可用。 */
    public static Verdict structureVerdict(FacilityRecord facility, WorldProbe probe) {
        if (facility == null) return Verdict.UNKNOWN;
        // 有施工对账时：图纸没收口（还有格没交代）就不算建好。total==0 表示"登记现有建筑/无蓝图"，
        // 不适用这一条。
        if (facility.construction().total() > 0 && !facility.construction().reconciled()) {
            return Verdict.NOT_USABLE;
        }
        DimAnchor inside = facility.entranceInside();
        DimAnchor outside = facility.entranceOutside();
        if (inside == null || outside == null) {
            return Verdict.NOT_USABLE;   // 没登记入口就没法确认可进可出
        }
        if (!probe.loaded(inside.dimension(), inside.x(), inside.y(), inside.z())
                || !probe.loaded(outside.dimension(), outside.x(), outside.y(), outside.z())) {
            return Verdict.UNKNOWN;
        }
        return Verdict.USABLE;
    }

    /** 第 3 层：生产条件。 */
    public static ProductionState productionState(FacilityRecord facility, WorldProbe probe) {
        if (facility == null) return ProductionState.UNKNOWN;
        return switch (facility.kind()) {
            case PASTURE_SHEEP -> animals(facility, probe, "minecraft:sheep");
            case PASTURE_COW -> animals(facility, probe, "minecraft:cow");
            case FARM -> crops(facility, probe);
            case TRADE -> trade(facility, probe);
            case HOUSE, STORAGE, GENERIC -> structureVerdict(facility, probe) == Verdict.USABLE
                    ? ProductionState.READY : ProductionState.NOT_READY;
        };
    }

    private static ProductionState animals(FacilityRecord facility, WorldProbe probe, String animalType) {
        if (!probe.loaded(facility.dimension(), facility.bounds().minX(), facility.bounds().minY(),
                facility.bounds().minZ())) {
            return ProductionState.UNKNOWN;
        }
        int count = 0;
        for (EntityView entity : probe.entities(facility.dimension(), facility.bounds())) {
            if (animalType.equals(entity.typeId())) count++;
        }
        return count >= MIN_ANIMALS ? ProductionState.READY : ProductionState.NOT_READY;
    }

    private static ProductionState crops(FacilityRecord facility, WorldProbe probe) {
        for (BlockBox zone : searchZones(facility)) {
            if (!probe.loaded(facility.dimension(), zone.minX(), zone.minY(), zone.minZ())) continue;
            int count = 0;
            for (int x = zone.minX(); x <= zone.maxX(); x++) {
                for (int z = zone.minZ(); z <= zone.maxZ(); z++) {
                    for (int y = zone.minY(); y <= zone.maxY(); y++) {
                        String id = probe.blockIdAt(facility.dimension(), x, y, z);
                        if (id != null && isCrop(id)) count++;
                    }
                }
            }
            if (count >= MIN_CROPS) return ProductionState.READY;
        }
        // 所有作业区都没加载 → 无法确认；有加载但没作物 → 未就绪。
        for (BlockBox zone : searchZones(facility)) {
            if (probe.loaded(facility.dimension(), zone.minX(), zone.minY(), zone.minZ())) {
                return ProductionState.NOT_READY;
            }
        }
        return ProductionState.UNKNOWN;
    }

    private static List<BlockBox> searchZones(FacilityRecord facility) {
        return facility.workZones().isEmpty() ? List.of(facility.bounds()) : facility.workZones();
    }

    private static boolean isCrop(String blockId) {
        for (String token : CROP_TOKENS) {
            if (blockId.contains(token)) return true;
        }
        return false;
    }

    private static ProductionState trade(FacilityRecord facility, WorldProbe probe) {
        if (!probe.loaded(facility.dimension(), facility.bounds().minX(), facility.bounds().minY(),
                facility.bounds().minZ())) {
            return ProductionState.UNKNOWN;
        }
        boolean villager = probe.entities(facility.dimension(), facility.bounds()).stream()
                .anyMatch(e -> "minecraft:villager".equals(e.typeId()));
        // 工作站绑定与实际可交易要另验（P0 只确认有村民在位）；所以这里最多到 READY。
        return villager ? ProductionState.READY : ProductionState.NOT_READY;
    }
}
