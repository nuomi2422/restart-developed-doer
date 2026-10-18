package com.dwinovo.numen.settlement.core.json;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;
import com.dwinovo.numen.settlement.core.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementJsonTest {

    private static FacilityRecord richFacility() {
        return new FacilityRecord(
                "pasture1", "owner", "base1", TestData.DIM, FacilityKind.PASTURE_SHEEP,
                BlockBox.of(0, 64, 0, 4, 68, 4), 1, "sheep_pen", "v1",
                BlockBox.of(-1, 63, -1, 5, 69, 5),
                List.of(ProtectionZone.structure(BlockBox.of(0, 64, 0, 4, 66, 0)),
                        ProtectionZone.space(BlockBox.of(2, 64, 0, 2, 66, 0))),
                List.of(BlockBox.of(1, 64, 1, 3, 66, 3)),
                DimAnchor.of(TestData.DIM, 2, 64, -1),
                DimAnchor.of(TestData.DIM, 2, 64, 0),
                List.of(DimAnchor.of(TestData.DIM, 2, 64, 2)),
                List.of(DimAnchor.of(TestData.DIM, 3, 64, 3)),
                List.of(CellKey.of(0, 0), CellKey.of(1, 0)),
                java.util.Map.of("minecraft:wheat", 12, "minecraft:bone_meal", 3),
                new ConstructionProgress(20, 1, 0, 21, 123L),
                Verdict.USABLE, ProductionState.NOT_READY, 999L, "note");
    }

    @Test
    void roundTripsEveryField() {
        FacilityRegistry original = new FacilityRegistry().with(richFacility());
        String json = SettlementJson.toJson(original);
        FacilityRegistry back = SettlementJson.fromJson(json);
        assertEquals(1, back.size());
        assertEquals(richFacility(), back.byId("pasture1").orElseThrow());
    }

    @Test
    void emptyRegistryRoundTrips() {
        String json = SettlementJson.toJson(new FacilityRegistry());
        assertTrue(SettlementJson.fromJson(json).isEmpty());
    }

    @Test
    void corruptJsonDegradesToEmptyInsteadOfThrowing() {
        assertTrue(SettlementJson.fromJson("{not json").isEmpty());
        assertTrue(SettlementJson.fromJson(null).isEmpty());
        assertTrue(SettlementJson.fromJson("").isEmpty());
    }
}
