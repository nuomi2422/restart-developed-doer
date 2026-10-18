package com.dwinovo.numen.settlement.core.model;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一处设施的登记真源（对应用户那张"每个设施最少记住什么"的表）。
 *
 * <p>字段分五组，与设计一一对应：
 * <ul>
 *   <li><b>身份</b>：{@link #id} / {@link #ownerId} / {@link #baseId} / {@link #dimension} / {@link #bounds}；</li>
 *   <li><b>是什么</b>：{@link #kind} / {@link #blueprintName} / {@link #blueprintVersion} / {@link #rotationQuarters}；</li>
 *   <li><b>从哪进/在哪操作</b>：{@link #entranceOutside} / {@link #entranceInside} / {@link #interactionPoints} / {@link #containers}；</li>
 *   <li><b>保护</b>：{@link #protectionBox} / {@link #zones}（作业区不在这里，是 {@link #workZones}，不产生硬禁）；</li>
 *   <li><b>进度与验收</b>：{@link #construction} / {@link #structureVerdict} / {@link #productionState} / {@link #lastCheckedAtEpochMs} / {@link #notes}。</li>
 * </ul>
 *
 * <p>入口只记"目标位置"，<b>不记路线</b>——具体走法交给寻路，存一整串过时路线只会误导。
 *
 * <p>这个记录是设施模块的<b>登记真源</b>；资产系统保存的是对规划有用的<b>投影</b>，
 * 两边不许各维护一份可独立修改的设施状态。
 *
 * <p>所有 List 字段在构造时归一为不可变；Gson 反序列化缺字段时传 null，这里统一兜底。
 */
public record FacilityRecord(
        String id,
        String ownerId,
        String baseId,
        String dimension,
        FacilityKind kind,
        BlockBox bounds,
        int rotationQuarters,
        String blueprintName,
        String blueprintVersion,
        BlockBox protectionBox,
        List<ProtectionZone> zones,
        List<BlockBox> workZones,
        DimAnchor entranceOutside,
        DimAnchor entranceInside,
        List<DimAnchor> interactionPoints,
        List<DimAnchor> containers,
        List<CellKey> cells,
        Map<String, Integer> storedItems,
        ConstructionProgress construction,
        Verdict structureVerdict,
        ProductionState productionState,
        long lastCheckedAtEpochMs,
        String notes) {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");

    public FacilityRecord {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("facility id must match [a-z0-9_-]{1,64}: " + id);
        }
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("facility dimension required");
        }
        if (kind == null) throw new IllegalArgumentException("facility kind required");
        if (bounds == null) throw new IllegalArgumentException("facility bounds required");
        zones = zones == null ? List.of() : List.copyOf(zones);
        workZones = workZones == null ? List.of() : List.copyOf(workZones);
        interactionPoints = interactionPoints == null ? List.of() : List.copyOf(interactionPoints);
        containers = containers == null ? List.of() : List.copyOf(containers);
        cells = cells == null ? List.of() : List.copyOf(cells);
        storedItems = storedItems == null ? Map.of() : Map.copyOf(storedItems);
        construction = construction == null ? ConstructionProgress.unknown() : construction;
        structureVerdict = structureVerdict == null ? Verdict.UNKNOWN : structureVerdict;
        productionState = productionState == null ? ProductionState.UNKNOWN : productionState;
    }

    /** 实际用于保护判定的范围：显式保护盒优先，否则退回设施占地。 */
    public BlockBox protectionBoxOrBounds() {
        return protectionBox != null ? protectionBox : bounds;
    }

    public boolean inDimension(String dim) {
        return dim != null && dim.equals(dimension);
    }

    public boolean coversColumn(int x, int z) {
        return bounds.containsColumn(x, z);
    }

    /** 验收结果回写：结构判定 + 生产判定 + 检查时间一次落定。 */
    public FacilityRecord withAcceptance(Verdict structure, ProductionState production, long atEpochMs) {
        return new FacilityRecord(id, ownerId, baseId, dimension, kind, bounds, rotationQuarters,
                blueprintName, blueprintVersion, protectionBox, zones, workZones,
                entranceOutside, entranceInside, interactionPoints, containers, cells, storedItems,
                construction, structure, production, atEpochMs, notes);
    }

    /** 施工进度回写（补料/续建后）。 */
    public FacilityRecord withConstruction(ConstructionProgress progress) {
        return new FacilityRecord(id, ownerId, baseId, dimension, kind, bounds, rotationQuarters,
                blueprintName, blueprintVersion, protectionBox, zones, workZones,
                entranceOutside, entranceInside, interactionPoints, containers, cells, storedItems,
                progress, structureVerdict, productionState, lastCheckedAtEpochMs, notes);
    }

    /** 登记设施里箱子的物品汇总（id→数量；扫容器后回写）。 */
    public FacilityRecord withStoredItems(Map<String, Integer> items) {
        return new FacilityRecord(id, ownerId, baseId, dimension, kind, bounds, rotationQuarters,
                blueprintName, blueprintVersion, protectionBox, zones, workZones,
                entranceOutside, entranceInside, interactionPoints, containers, cells, items,
                construction, structureVerdict, productionState, lastCheckedAtEpochMs, notes);
    }

    public FacilityRecord withZones(List<ProtectionZone> nextZones, List<BlockBox> nextWorkZones) {
        return new FacilityRecord(id, ownerId, baseId, dimension, kind, bounds, rotationQuarters,
                blueprintName, blueprintVersion, protectionBox, nextZones, nextWorkZones,
                entranceOutside, entranceInside, interactionPoints, containers, cells, storedItems,
                construction, structureVerdict, productionState, lastCheckedAtEpochMs, notes);
    }
}
