package com.dwinovo.numen.settlement.core.protect;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtectionRulesTest {

    private static final BlockClassifier CLASSIFIER = new SimpleBlockClassifier();

    private static FacilityRegistry registry() {
        FacilityRecord f = TestData.facility("pasture1", FacilityKind.PASTURE_SHEEP,
                BlockBox.of(10, 64, 10, 20, 68, 20),
                List.of(ProtectionZone.structure(BlockBox.of(10, 64, 10, 12, 66, 12)),
                        ProtectionZone.space(BlockBox.of(13, 64, 10, 13, 66, 10))),
                List.of(BlockBox.of(15, 64, 15, 19, 64, 19)),
                null, null, com.dwinovo.numen.settlement.core.model.ConstructionProgress.unknown());
        return new FacilityRegistry().with(f);
    }

    @Test
    void structureCellsCannotBeBrokenOrReplaced() {
        FacilityRegistry r = registry();
        assertEquals(Decision.DENY, ProtectionRules.decideBreak(r, TestData.DIM, 11, 65, 11, List.of(), 0L));
        assertEquals(Decision.DENY, ProtectionRules.decide(r, TestData.DIM, 11, 65, 11,
                Action.REPLACE, "minecraft:air", CLASSIFIER));
        assertEquals(Decision.DENY, ProtectionRules.decide(r, TestData.DIM, 11, 65, 11,
                Action.PLACE, "minecraft:stone", CLASSIFIER));
    }

    @Test
    void entrancePassageRefusesSolidButAllowsDecoration() {
        FacilityRegistry r = registry();
        // a solid block would seal the doorway -> deny
        assertEquals(Decision.DENY, ProtectionRules.decidePlace(r, TestData.DIM, 13, 65, 10,
                "minecraft:stone", CLASSIFIER, List.of(), 0L));
        // a torch does not obstruct -> allow
        assertEquals(Decision.ALLOW, ProtectionRules.decidePlace(r, TestData.DIM, 13, 65, 10,
                "minecraft:torch", CLASSIFIER, List.of(), 0L));
        // breaking a block in the passage is fine
        assertEquals(Decision.ALLOW, ProtectionRules.decideBreak(r, TestData.DIM, 13, 65, 10, List.of(), 0L));
    }

    @Test
    void workZonesAndOpenSpaceAreNotLocked() {
        FacilityRegistry r = registry();
        // inside the facility bounds but not in any protection zone (the farmable area)
        assertEquals(Decision.ALLOW, ProtectionRules.decideBreak(r, TestData.DIM, 16, 65, 16, List.of(), 0L));
        assertEquals(Decision.ALLOW, ProtectionRules.decidePlace(r, TestData.DIM, 16, 65, 16,
                "minecraft:wheat", CLASSIFIER, List.of(), 0L));
    }

    @Test
    void grantLetsTheOwningTaskRepairItsOwnStructure() {
        FacilityRegistry r = registry();
        Grant grant = Grant.of("pasture1", TestData.DIM, BlockBox.of(10, 64, 10, 20, 68, 20));
        assertEquals(Decision.ALLOW, ProtectionRules.decideBreak(r, TestData.DIM, 11, 65, 11,
                List.of(grant), 0L));
        assertEquals(Decision.ALLOW, ProtectionRules.decidePlace(r, TestData.DIM, 11, 65, 11,
                "minecraft:oak_fence", CLASSIFIER, List.of(grant), 0L));
    }

    @Test
    void expiredGrantIsIgnored() {
        FacilityRegistry r = registry();
        Grant grant = Grant.expiring("pasture1", TestData.DIM,
                BlockBox.of(10, 64, 10, 20, 68, 20), 1000L);
        long now = 2000L;
        assertEquals(Decision.DENY, ProtectionRules.decideBreak(r, TestData.DIM, 11, 65, 11,
                List.of(grant), now));
    }

    @Test
    void otherDimensionsAreUnaffected() {
        FacilityRegistry r = registry();
        assertEquals(Decision.ALLOW, ProtectionRules.decideBreak(r, "minecraft:the_nether",
                11, 65, 11, List.of(), 0L));
    }

    @Test
    void placeObstructionOverloadMatchesClassifierRules() {
        FacilityRegistry r = registry();
        // STRUCTURE: even a passable block is refused
        assertEquals(Decision.DENY, ProtectionRules.decidePlaceObstruction(r, TestData.DIM,
                11, 65, 11, false, List.of(), 0L));
        // SPACE: obstructing refused, passable allowed
        assertEquals(Decision.DENY, ProtectionRules.decidePlaceObstruction(r, TestData.DIM,
                13, 65, 10, true, List.of(), 0L));
        assertEquals(Decision.ALLOW, ProtectionRules.decidePlaceObstruction(r, TestData.DIM,
                13, 65, 10, false, List.of(), 0L));
    }
}
