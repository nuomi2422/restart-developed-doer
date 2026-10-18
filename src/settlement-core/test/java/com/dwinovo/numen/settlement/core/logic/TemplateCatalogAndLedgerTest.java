package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.ConstructionStatus;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.Verdict;
import com.dwinovo.numen.settlement.core.TestData;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模板目录（V2：7×7 一格一模块 + 14×14 一级地皮）与放置账本/续建。 */
class TemplateCatalogAndLedgerTest {

    private static final String DIM = TestData.DIM;

    // ── 目录 ─────────────────────────────────────────────────────────

    @Test
    void catalogHasTheV2Templates() {
        assertEquals(6, TemplateCatalog.all().size());
        for (String id : new String[]{"pen_sheep", "pen_cow", "core_house", "farm_basic",
                "trade_post", "platform_cobble14"}) {
            assertTrue(TemplateCatalog.has(id), "缺 " + id);
        }
    }

    @Test
    void modulesAreOneCellEachPlatformIsTwo() {
        // 用户 V2 规则：模块统一 7×7 各占一格；一级地皮 14×14 = 2×2 格
        for (String id : new String[]{"pen_sheep", "pen_cow", "core_house", "farm_basic", "trade_post"}) {
            assertEquals(1, TemplateCatalog.byId(id).orElseThrow().footprintCellsX(), id);
        }
        assertEquals(2, TemplateCatalog.byId("platform_cobble14").orElseThrow().footprintCellsX());
        for (FacilityTemplate t : TemplateCatalog.all()) {
            assertTrue(t.footprintFits(TemplateCatalog.DEFAULT_CELL_SIZE),
                    t.id() + " 的占地必须容得下自己的蓝图尺寸");
        }
    }

    @Test
    void maturitySeparatesFileFromFullyAutomated() {
        assertEquals(FacilityTemplate.Maturity.READY,
                TemplateCatalog.byId("pen_sheep").orElseThrow().maturity());
        assertEquals(FacilityTemplate.Maturity.PARTIAL,
                TemplateCatalog.byId("core_house").orElseThrow().maturity());
        assertEquals(FacilityTemplate.Maturity.STRUCTURE_ONLY,
                TemplateCatalog.byId("farm_basic").orElseThrow().maturity());
        assertEquals(FacilityTemplate.Maturity.READY,
                TemplateCatalog.byId("trade_post").orElseThrow().maturity());

        FacilityTemplate farm = TemplateCatalog.byId("farm_basic").orElseThrow();
        assertTrue(farm.needsWater(), "农田必须标注灌水未自动化");
    }

    @Test
    void penEntranceIsAFenceGateNotCarpet() {
        FacilityTemplate pen = TemplateCatalog.byId("pen_sheep").orElseThrow();
        assertTrue(pen.materials().stream().anyMatch(m -> m.itemId().contains("fence_gate")));
        assertFalse(pen.materials().stream().anyMatch(m -> m.itemId().contains("carpet")));
    }

    @Test
    void anchorYOffsetDiffersPerTemplateBecauseGeneratorsDiffer() {
        assertEquals(0, TemplateCatalog.byId("pen_sheep").orElseThrow().anchorYOffset());
        assertEquals(0, TemplateCatalog.byId("core_house").orElseThrow().anchorYOffset());
        assertEquals(-1, TemplateCatalog.byId("farm_basic").orElseThrow().anchorYOffset());
        assertEquals(-6, TemplateCatalog.byId("platform_cobble14").orElseThrow().anchorYOffset());
    }

    @Test
    void fittingFiltersByAvailableCells() {
        // 1×1 格能放：5 个建筑模块；地皮要 2×2
        assertEquals(5, TemplateCatalog.fitting(1, 1).size());
        assertEquals(6, TemplateCatalog.fitting(2, 2).size());
        assertEquals(0, TemplateCatalog.fitting(0, 0).size());
    }

    @Test
    void materialGapReportsShortfallOnly() {
        FacilityTemplate pen = TemplateCatalog.byId("pen_sheep").orElseThrow();
        // 7×7 周长 24 格：1 格换门 → 23 栅栏 + 1 门
        Map<String, Integer> gap = TemplateCatalog.materialGap(pen, Map.of("minecraft:oak_fence", 10));
        assertEquals(13, gap.get("minecraft:oak_fence"));
        assertEquals(1, gap.get("minecraft:oak_fence_gate"));
        assertTrue(TemplateCatalog.materialGap(pen,
                Map.of("minecraft:oak_fence", 23, "minecraft:oak_fence_gate", 1)).isEmpty());
    }

    @Test
    void materialListsMatchTheRealGenerators() {
        // 7×7 屋：地板 49 + 顶 49 + 墙 46 = 144 木板
        FacilityTemplate house = TemplateCatalog.byId("core_house").orElseThrow();
        assertEquals(144, house.materials().stream()
                .filter(m -> m.itemId().endsWith("oak_planks")).findFirst().orElseThrow().count());
        // 7×7 农田：49 支撑 + 48 耕地（耕地按泥土记账）= 97 泥土
        FacilityTemplate farm = TemplateCatalog.byId("farm_basic").orElseThrow();
        assertEquals(97, farm.materials().stream()
                .filter(m -> m.itemId().endsWith("dirt")).findFirst().orElseThrow().count());
        // 交易所：两层围墙 48 环格 − 门格 − 门上一格 = 46 栅栏；4 张床
        FacilityTemplate trade = TemplateCatalog.byId("trade_post").orElseThrow();
        assertEquals(46, trade.materials().stream()
                .filter(m -> m.itemId().endsWith("oak_fence")).findFirst().orElseThrow().count());
        assertEquals(4, trade.materials().stream()
                .filter(m -> m.itemId().endsWith("white_bed")).findFirst().orElseThrow().count());
    }

    @Test
    void unknownTemplateIsEmpty() {
        assertTrue(TemplateCatalog.byId("nope").isEmpty());
        assertTrue(TemplateCatalog.byId(null).isEmpty());
    }

    // ── 去重 / 续建 ──────────────────────────────────────────────────

    private static FacilityRecord tracked(String id, ConstructionStatus status,
                                          int completed, int total, String template,
                                          DimAnchor anchor, int rotation) {
        ConstructionProgress c = new ConstructionProgress(completed, 0, 0, total, 1L, status,
                template, anchor, "t-1", Map.of(), null);
        return new FacilityRecord(id, "owner", "base", DIM, FacilityKind.PASTURE_SHEEP,
                BlockBox.of(7, 64, 7, 13, 69, 13),
                rotation, null, null, null, List.of(), List.of(), null, null,
                List.of(), List.of(), List.of(), Map.of(),
                c, Verdict.UNKNOWN, ProductionState.UNKNOWN, 1L, null);
    }

    @Test
    void sameIdTwiceDoesNotCreateASecondFacility() {
        String id = PlacementLedger.facilityId("base", "pen_sheep", "A2");
        FacilityRegistry reg = new FacilityRegistry().with(tracked(id, ConstructionStatus.BUILDING,
                3, 20, "pen_sheep", DimAnchor.of(DIM, 7, 64, 7), 0));
        PlacementLedger.Decision d = PlacementLedger.decide(reg, id);
        assertEquals(PlacementLedger.Disposition.RESUME, d.disposition());
        assertNotNull(d.existing());
        assertTrue(d.disposition().canStart());
    }

    @Test
    void completedFacilityRejectsRepeatPlacement() {
        String id = PlacementLedger.facilityId("base", "pen_sheep", "A2");
        FacilityRegistry reg = new FacilityRegistry().with(tracked(id, ConstructionStatus.COMPLETE,
                20, 20, "pen_sheep", DimAnchor.of(DIM, 7, 64, 7), 0));
        PlacementLedger.Decision d = PlacementLedger.decide(reg, id);
        assertEquals(PlacementLedger.Disposition.ALREADY_COMPLETE, d.disposition());
        assertFalse(d.disposition().canStart());
    }

    @Test
    void failedNeedsExplicitResume() {
        String id = PlacementLedger.facilityId("base", "pen_sheep", "A2");
        FacilityRegistry reg = new FacilityRegistry().with(tracked(id, ConstructionStatus.FAILED,
                5, 20, "pen_sheep", DimAnchor.of(DIM, 7, 64, 7), 0));
        assertEquals(PlacementLedger.Disposition.FAILED_NEEDS_RESUME,
                PlacementLedger.decide(reg, id).disposition());
    }

    @Test
    void untrackedManualRegistrationIsNotSilentlyOverwritten() {
        FacilityRegistry reg = new FacilityRegistry().with(
                TestData.facility("base_pen_sheep_a2", FacilityKind.PASTURE_SHEEP,
                        BlockBox.of(7, 64, 7, 13, 69, 13)));
        PlacementLedger.Decision d = PlacementLedger.decide(reg, "base_pen_sheep_a2");
        assertEquals(PlacementLedger.Disposition.ALREADY_COMPLETE, d.disposition());
        assertFalse(d.disposition().canStart());
    }

    @Test
    void facilityIdIsDeterministicAndStable() {
        String a = PlacementLedger.facilityId("base", "pen_sheep", "A2");
        String b = PlacementLedger.facilityId("base", "pen_sheep", "A2");
        String c = PlacementLedger.facilityId("base", "pen_sheep", "A3");
        assertEquals(a, b);
        assertFalse(a.equals(c));
        assertTrue(a.matches("[a-z0-9_-]{1,64}"), a);
    }

    @Test
    void resumeMismatchDetectsChangedAnchor() {
        FacilityTemplate pen = TemplateCatalog.byId("pen_sheep").orElseThrow();
        PlatformPlan plan = new PlatformPlan("base", DIM, 0, 64, 0, 5, 5, 7, 0, 4, List.of(), List.of());
        PlacementMath.PlacementPlan resolved = PlacementMath.resolve(plan, CellKey.of(1, 1), pen, 0);

        FacilityRecord ok = tracked("x", ConstructionStatus.BUILDING, 3, 20, "pen_sheep",
                resolved.anchor(), 0);
        assertTrue(PlacementLedger.resumeMismatches(ok, pen, resolved).isEmpty());

        FacilityRecord moved = tracked("x", ConstructionStatus.BUILDING, 3, 20, "pen_sheep",
                DimAnchor.of(DIM, 100, 65, 100), 0);
        assertFalse(PlacementLedger.resumeMismatches(moved, pen, resolved).isEmpty());

        FacilityRecord rotated = tracked("x", ConstructionStatus.BUILDING, 3, 20, "pen_sheep",
                resolved.anchor(), 1);
        assertFalse(PlacementLedger.resumeMismatches(rotated, pen, resolved).isEmpty());
    }

    @Test
    void openLedgerIsImmediatelyOpenSoRestartShowsBuildingNotEmpty() {
        FacilityTemplate pen = TemplateCatalog.byId("pen_sheep").orElseThrow();
        PlatformPlan plan = new PlatformPlan("base", DIM, 0, 64, 0, 5, 5, 7, 0, 4, List.of(), List.of());
        PlacementMath.PlacementPlan resolved = PlacementMath.resolve(plan, CellKey.of(1, 1), pen, 0);
        ConstructionProgress opened = PlacementLedger.openLedger(pen, resolved, "t-9", 20, 5L);
        assertTrue(opened.isOpen());
        assertEquals(ConstructionStatus.PLANNED, opened.status());
        assertEquals("pen_sheep", opened.template());
        assertEquals(resolved.anchor(), opened.anchor());
        assertEquals(20, opened.total());
    }
}
