package com.dwinovo.numen.settlement.core.accept;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcceptanceEvaluatorTest {

    private static final BlockBox BOUNDS = BlockBox.of(0, 64, 0, 4, 68, 4);
    private static final DimAnchor OUT = DimAnchor.of(TestData.DIM, 2, 64, -1);
    private static final DimAnchor IN = DimAnchor.of(TestData.DIM, 2, 64, 0);

    @Test
    void unreconciledConstructionIsNotUsable() {
        FacilityRecord f = TestData.facility("p", FacilityKind.PASTURE_SHEEP, BOUNDS,
                List.of(), List.of(), OUT, IN, new ConstructionProgress(5, 0, 0, 10, 0L));
        FakeProbe probe = FakeProbe.loadedAll();
        assertEquals(Verdict.NOT_USABLE, AcceptanceEvaluator.structureVerdict(f, probe));
    }

    @Test
    void reconciledWithLoadedEntrancesIsUsable() {
        FacilityRecord f = TestData.facility("p", FacilityKind.PASTURE_SHEEP, BOUNDS,
                List.of(), List.of(), OUT, IN, new ConstructionProgress(10, 0, 0, 10, 0L));
        assertEquals(Verdict.USABLE, AcceptanceEvaluator.structureVerdict(f, FakeProbe.loadedAll()));
    }

    @Test
    void registeredExistingBuildingNeedsNoReconciliation() {
        FacilityRecord f = TestData.facility("p", FacilityKind.HOUSE, BOUNDS,
                List.of(), List.of(), OUT, IN, ConstructionProgress.unknown());
        assertEquals(Verdict.USABLE, AcceptanceEvaluator.structureVerdict(f, FakeProbe.loadedAll()));
    }

    @Test
    void missingEntranceIsNotUsable() {
        FacilityRecord f = TestData.facility("p", FacilityKind.HOUSE, BOUNDS,
                List.of(), List.of(), null, null, ConstructionProgress.unknown());
        assertEquals(Verdict.NOT_USABLE, AcceptanceEvaluator.structureVerdict(f, FakeProbe.loadedAll()));
    }

    @Test
    void unloadedEntranceIsUnknownNotMissing() {
        FacilityRecord f = TestData.facility("p", FacilityKind.HOUSE, BOUNDS,
                List.of(), List.of(), OUT, IN, new ConstructionProgress(10, 0, 0, 10, 0L));
        assertEquals(Verdict.UNKNOWN, AcceptanceEvaluator.structureVerdict(f, new FakeProbe()));
    }

    @Test
    void pastureIsReadyOnlyWhenAnimalsArePresent() {
        FacilityRecord f = TestData.facility("p", FacilityKind.PASTURE_SHEEP, BOUNDS,
                List.of(), List.of(), OUT, IN, new ConstructionProgress(10, 0, 0, 10, 0L));
        FakeProbe empty = FakeProbe.loadedAll();
        assertEquals(ProductionState.NOT_READY, AcceptanceEvaluator.productionState(f, empty));
        FakeProbe withSheep = FakeProbe.loadedAll().withEntity("minecraft:sheep", 2, 64, 2);
        assertEquals(ProductionState.READY, AcceptanceEvaluator.productionState(f, withSheep));
    }

    @Test
    void pastureProductionUnknownWhenUnloaded() {
        FacilityRecord f = TestData.facility("p", FacilityKind.PASTURE_COW, BOUNDS,
                List.of(), List.of(), OUT, IN, new ConstructionProgress(10, 0, 0, 10, 0L));
        assertEquals(ProductionState.UNKNOWN, AcceptanceEvaluator.productionState(f, new FakeProbe()));
    }

    @Test
    void farmIsReadyOnlyWithCrops() {
        BlockBox field = BlockBox.of(1, 64, 1, 3, 64, 3);
        FacilityRecord f = TestData.facility("farm", FacilityKind.FARM, BOUNDS,
                List.of(), List.of(field), OUT, IN, new ConstructionProgress(10, 0, 0, 10, 0L));
        assertEquals(ProductionState.NOT_READY,
                AcceptanceEvaluator.productionState(f, FakeProbe.loadedAll()));
        FakeProbe planted = FakeProbe.loadedAll().withBlock("minecraft:wheat", 2, 64, 2);
        assertEquals(ProductionState.READY, AcceptanceEvaluator.productionState(f, planted));
    }

    @Test
    void tradeIsReadyWhenAVillagerIsPresent() {
        FacilityRecord f = TestData.facility("trade", FacilityKind.TRADE, BOUNDS,
                List.of(), List.of(), OUT, IN, new ConstructionProgress(10, 0, 0, 10, 0L));
        FakeProbe withVillager = FakeProbe.loadedAll().withEntity("minecraft:villager", 2, 64, 2);
        assertEquals(ProductionState.READY, AcceptanceEvaluator.productionState(f, withVillager));
        assertEquals(ProductionState.NOT_READY,
                AcceptanceEvaluator.productionState(f, FakeProbe.loadedAll()));
    }

    /** 可控的世界探针：默认"什么都看不到"，loadedAll 打开后看到登记过的方块/实体。 */
    static final class FakeProbe implements WorldProbe {
        private final Map<String, String> blocks = new HashMap<>();
        private final Set<String> loaded = new HashSet<>();
        private final List<EntityView> entities = new ArrayList<>();
        private boolean loadedAll = false;

        static FakeProbe loadedAll() {
            FakeProbe probe = new FakeProbe();
            probe.loadedAll = true;
            return probe;
        }

        FakeProbe withBlock(String blockId, int x, int y, int z) {
            blocks.put(key(TestData.DIM, x, y, z), blockId);
            return this;
        }

        FakeProbe withEntity(String typeId, double x, double y, double z) {
            entities.add(new EntityView(typeId, x, y, z));
            return this;
        }

        private static String key(String dim, int x, int y, int z) {
            return dim + "|" + x + "|" + y + "|" + z;
        }

        @Override
        public boolean loaded(String dimension, int x, int y, int z) {
            return loadedAll || loaded.contains(key(dimension, x, y, z));
        }

        @Override
        public String blockIdAt(String dimension, int x, int y, int z) {
            return blocks.get(key(dimension, x, y, z));
        }

        @Override
        public List<EntityView> entities(String dimension, BlockBox box) {
            return entities;
        }
    }
}
