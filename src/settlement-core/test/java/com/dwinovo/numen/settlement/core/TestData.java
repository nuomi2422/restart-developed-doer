package com.dwinovo.numen.settlement.core;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;
import com.dwinovo.numen.settlement.core.model.Verdict;

import java.util.List;

/** 测试用设施工厂（真实构造器参数多，集中在这里，测试只关心差异项）。 */
public final class TestData {

    public static final String DIM = "minecraft:overworld";

    private TestData() {}

    public static FacilityRecord facility(String id, FacilityKind kind, BlockBox bounds) {
        return facility(id, kind, bounds, List.of(), List.of(), null, null,
                ConstructionProgress.unknown());
    }

    public static FacilityRecord facility(String id, FacilityKind kind, BlockBox bounds,
                                          List<ProtectionZone> zones, List<BlockBox> workZones,
                                          DimAnchor outside, DimAnchor inside,
                                          ConstructionProgress progress) {
        return new FacilityRecord(id, "owner", "base", DIM, kind, bounds, 0,
                "bp", "v1", null, zones, workZones, outside, inside,
                List.of(), List.of(), List.of(), java.util.Map.of(), progress,
                Verdict.UNKNOWN, ProductionState.UNKNOWN, 0L, null);
    }
}
