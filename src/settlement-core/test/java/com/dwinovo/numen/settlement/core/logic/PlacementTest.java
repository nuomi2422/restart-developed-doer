package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 放置坐标数学与校验（V2：一格 = 一个 7×7 模块）。 */
class PlacementTest {

    private static final String DIM = TestData.DIM;

    /** 原点 (0,64,0)、5×5 格、每格 7（V2 一级地皮的网格）。 */
    private static PlatformPlan plan() {
        return new PlatformPlan("base", DIM, 0, 64, 0, 5, 5, 7, 0, 4, List.of(), List.of());
    }

    private static FacilityTemplate pen() {
        return TemplateCatalog.byId("pen_sheep").orElseThrow();
    }

    // ── 坐标映射 ─────────────────────────────────────────────────────

    @Test
    void cellToWorldCoordinatesAreDeterministic() {
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen(), 0);
        // B2 = cx=1,cz=1 → 7..13；羊圈 7×7 占 1 格
        assertEquals(7, p.footprintBox().minX());
        assertEquals(7, p.footprintBox().minZ());
        assertEquals(13, p.footprintBox().maxX());
        assertEquals(13, p.footprintBox().maxZ());
        // 栅栏就在 floorY 这一层（anchorYOffset=0）
        assertEquals(64, p.anchor().y());
        assertEquals(7, p.anchor().x());
        assertEquals(7, p.anchor().z());
    }

    @Test
    void modulesMaySitOnTopOfTheFoundationPlatform() {
        // ★ V2 用户流程："先修 14×14 圆石地皮，再往上面放 7×7 模块"。
        // 地皮是地面覆盖不是圈地：模块与地皮互相放行；模块之间、地皮之间照旧互斥。
        PlatformPlan plan = plan();
        FacilityTemplate platform = TemplateCatalog.byId("platform_cobble14").orElseThrow();
        // 地皮的登记记录：施工账带模板名（豁免判据读它）
        FacilityRecord pad = new FacilityRecord("pad1", "owner", "base", DIM, FacilityKind.GENERIC,
                BlockBox.of(0, 63, 0, 13, 65, 13), 0, "pad1", "v1", null,
                List.of(), List.of(), null, null, List.of(), List.of(),
                List.of(com.dwinovo.numen.settlement.core.model.CellKey.of(0, 0),
                        com.dwinovo.numen.settlement.core.model.CellKey.of(1, 1)),
                java.util.Map.of(),
                new com.dwinovo.numen.settlement.core.model.ConstructionProgress(
                        14 * 14, 0, 0, 14 * 14, 1L,
                        com.dwinovo.numen.settlement.core.model.ConstructionStatus.COMPLETE,
                        "platform_cobble14",
                        DimAnchor.of(DIM, 0, 63, 0), "t0", java.util.Map.of(), null),
                com.dwinovo.numen.settlement.core.model.Verdict.UNKNOWN,
                com.dwinovo.numen.settlement.core.model.ProductionState.UNKNOWN, 1L, null);
        FacilityRegistry reg = new FacilityRegistry().with(pad);
        // 模块放在地皮上 → 放行（A1/B2 都在 pad 的 2×2 格里）
        assertTrue(PlacementValidator.validate(plan, CellKey.of(0, 0), pen(), 0, reg, DIM).ok(),
                "7×7 模块必须能放在 14×14 一级地皮上: "
                        + PlacementValidator.validate(plan, CellKey.of(0, 0), pen(), 0, reg, DIM).rejects());
        // 再放一块地皮压在旧地皮上 → 拒绝
        assertFalse(PlacementValidator.validate(plan, CellKey.of(0, 0), platform, 0, reg, DIM).ok());
    }

    @Test
    void allBuildingModulesAreOneCell() {
        // V2 用户规则：模块统一 7×7、各占一格、互不重合
        for (String id : new String[]{"pen_sheep", "pen_cow", "core_house", "farm_basic", "trade_post"}) {
            FacilityTemplate t = TemplateCatalog.byId(id).orElseThrow();
            assertEquals(7, t.sizeX(), id + " 宽必须是 7");
            assertEquals(7, t.sizeZ(), id + " 长必须是 7");
            assertEquals(1, t.footprintCellsX(), id + " 必须占 1 格（cellSize=7）");
            assertEquals(1, t.footprintCellsZ(), id);
            assertTrue(t.footprintFits(TemplateCatalog.DEFAULT_CELL_SIZE), id);
        }
        // 一级地皮 14×14 = 2×2 格
        FacilityTemplate platform = TemplateCatalog.byId("platform_cobble14").orElseThrow();
        assertEquals(14, platform.sizeX());
        assertEquals(2, platform.footprintCellsX());
        assertTrue(platform.footprintFits(TemplateCatalog.DEFAULT_CELL_SIZE));
    }

    @Test
    void occupiedCellsCoverFootprint() {
        FacilityTemplate platform = TemplateCatalog.byId("platform_cobble14").orElseThrow();
        List<CellKey> cells = PlacementMath.occupiedCells(plan(), CellKey.of(1, 2), platform);
        assertEquals(4, cells.size());
        assertTrue(cells.contains(CellKey.of(1, 2)));
        assertTrue(cells.contains(CellKey.of(2, 3)));
    }

    @Test
    void footprintBoxYFollowsTheTemplateAnchorOffset() {
        // ★ 2026-10-09 实机：农田登记 y=68..69，而支撑泥土层在 y=67——范围必须带偏移
        PlacementMath.PlacementPlan farm = PlacementMath.resolve(plan(), CellKey.of(0, 0),
                TemplateCatalog.byId("farm_basic").orElseThrow(), 0);
        assertEquals(63, farm.footprintBox().minY(), "农田支撑层在 floorY-1");
        assertEquals(64, farm.footprintBox().maxY());
        assertEquals(farm.anchor().y(), farm.footprintBox().minY(), "范围底 = 锚点 y");

        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen(), 0);
        assertEquals(64, p.footprintBox().minY(), "栅栏就在 floorY");
        assertEquals(65, p.footprintBox().maxY());
    }

    // ── 旋转 ─────────────────────────────────────────────────────────

    @Test
    void rotationSwapsSizesAndMovesEntrance() {
        FacilityTemplate pen = pen();   // 7×7，入口在北侧中间 (3,0)
        PlacementMath.PlacementPlan r0 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 0);
        // rotation 的单位是「顺时针四分之一圈」，不是角度（宿主把 90° 换算成 1）
        PlacementMath.PlacementPlan r90 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 1);

        assertEquals(r0.footprintBox().minX(), r90.footprintBox().minX());
        assertEquals(0 + 6, r90.entranceInside().x(), "顺时针 90° 后门在东侧");
        assertEquals(0 + 3, r90.entranceInside().z());
        assertEquals(0 + 3, r0.entranceInside().x(), "未旋转时门在北侧中间");
        assertEquals(0 + 0, r0.entranceInside().z());
    }

    @Test
    void rotationNormalizesAndNonSquareSwapsFootprint() {
        assertEquals(0, RotationMath.normalizeQuarters(4));
        assertEquals(1, RotationMath.normalizeQuarters(5));
        assertEquals(3, RotationMath.normalizeQuarters(-1));
        assertEquals(2, RotationMath.rotatedSizeX(3, 2, 1));
        assertEquals(3, RotationMath.rotatedSizeZ(3, 2, 1));
        assertEquals(3, RotationMath.rotatedSizeX(3, 2, 2));
    }

    @Test
    void rotationIsClockwiseConsistentForAllQuarters() {
        assertEquals(5, RotationMath.rotateLocal(0, 0, 4, 6, 1).x());
        assertEquals(0, RotationMath.rotateLocal(0, 0, 4, 6, 1).z());
        assertEquals(3, RotationMath.rotateLocal(0, 0, 4, 6, 2).x());
        assertEquals(5, RotationMath.rotateLocal(0, 0, 4, 6, 2).z());
        assertEquals(0, RotationMath.rotateLocal(0, 0, 4, 6, 3).x());
        assertEquals(3, RotationMath.rotateLocal(0, 0, 4, 6, 3).z());
    }

    // ── 校验：越界 / 重叠 / 入口 ──────────────────────────────────────

    @Test
    void acceptsAFreeCell() {
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, new FacilityRegistry(), DIM);
        assertTrue(r.ok(), "空格必须放行: " + r.rejects());
    }

    @Test
    void rejectsOutOfBoundsBeforeTouchingWorld() {
        // 2×2 格的地皮放在最后一格 → 越界
        FacilityTemplate platform = TemplateCatalog.byId("platform_cobble14").orElseThrow();
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(4, 4),
                platform, 0, new FacilityRegistry(), DIM);
        assertFalse(r.ok());
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("OUT_OF_BOUNDS")), r.rejects().toString());
    }

    @Test
    void rejectsOverlapWithExistingFacility() {
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "pen_a", FacilityKind.PASTURE_SHEEP, BlockBox.of(7, 64, 7, 13, 69, 13)));
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, reg, DIM);
        assertFalse(r.ok(), "重叠必须拒绝");
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("OVERLAP")), r.rejects().toString());
    }

    @Test
    void adjacentButNotOverlappingIsAccepted() {
        // 一格一模块、相邻独立摆放（用户的 V2 规则）
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "pen_a", FacilityKind.PASTURE_SHEEP, BlockBox.of(0, 64, 0, 6, 69, 6)));
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 0),
                pen(), 0, reg, DIM);
        assertTrue(r.ok(), "相邻格必须放行（门朝北出格不算占用）: " + r.rejects());
    }

    @Test
    void rejectsWhenEntranceClearanceIsBlocked() {
        // 羊圈入口在北侧；北边一格被别的设施占住 → 门被堵
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "blocker", FacilityKind.HOUSE, BlockBox.of(7, 64, 0, 13, 69, 6)));
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, reg, DIM);
        assertFalse(r.ok(), "入口被堵必须拒绝");
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("ENTRANCE_BLOCKED")), r.rejects().toString());
    }

    @Test
    void resumeIgnoresItsOwnOverlap() {
        FacilityRegistry reg = new FacilityRegistry().with(TestData.facility(
                "pen_b_sheep_a2", FacilityKind.PASTURE_SHEEP, BlockBox.of(7, 64, 7, 13, 69, 13)));
        assertFalse(PlacementValidator.validate(plan(), CellKey.of(1, 1), pen(), 0, reg, DIM).ok());
        assertTrue(PlacementValidator.validate(plan(), CellKey.of(1, 1), pen(), 0, reg, DIM,
                "pen_b_sheep_a2").ok());
    }

    @Test
    void rejectsCellAlreadyClaimedByRegistryCells() {
        // 已有设施真实落在 B2（bounds 7..13 × 7..13）→ 新模块放 B2 必须拒绝。
        // ★ 2026-10-09 晚修：占位判据改用 bounds 现算；这里额外把 cells 写成无关的 (4,4)，
        //   证明"看的是真实 bounds，不是陈旧快照"。
        FacilityRecord claiming = new FacilityRecord(
                "claimer", "owner", "base", DIM, FacilityKind.GENERIC,
                BlockBox.of(7, 64, 7, 13, 69, 13), 0, null, null, null,
                List.of(), List.of(), null, null, List.of(), List.of(),
                List.of(CellKey.of(4, 4)), java.util.Map.of(),
                null, null, null, 0L, null);
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, new FacilityRegistry().with(claiming), DIM);
        assertFalse(r.ok(), "格已被登记占用必须拒绝");
        assertTrue(r.rejects().stream().anyMatch(s -> s.startsWith("CELL_TAKEN")), r.rejects().toString());
    }

    @Test
    void staleRegistryCellsDoNotFalselyBlockAnotherCell() {
        // ★ 2026-10-09 实机抓到的缺陷：设施真实 bounds 只在 E5，而陈旧 cells 快照记着 B2
        //   （基地基准改过导致）→ 旧代码让"放 B2"误报 CELL_TAKEN。改按 bounds 现算后必须放行。
        FacilityRecord stale = new FacilityRecord(
                "old", "owner", "base", DIM, FacilityKind.GENERIC,
                BlockBox.of(28, 64, 28, 34, 69, 34), 0, null, null, null,
                List.of(), List.of(), null, null, List.of(), List.of(),
                List.of(CellKey.of(1, 1)), java.util.Map.of(),
                null, null, null, 0L, null);
        PlacementValidator.Result r = PlacementValidator.validate(plan(), CellKey.of(1, 1),
                pen(), 0, new FacilityRegistry().with(stale), DIM);
        assertTrue(r.ok(), "陈旧 cells 不许误拒（按 bounds 现算）: " + r.rejects());
    }

    // ── 入口站位 ─────────────────────────────────────────────────────

    @Test
    void entranceOutsideIsOneCellBeyondInside() {
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen(), 0);
        DimAnchor in = p.entranceInside();
        DimAnchor out = p.entranceOutside();
        assertEquals(in.x(), out.x());
        assertEquals(in.z() - 1, out.z(), "门朝北，门外站位在北边一格");
    }

    @Test
    void clearanceBoxExtendsAwayFromEntrance() {
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen(), 0);
        BlockBox c = PlacementMath.clearanceBox(p, pen());
        assertEquals(p.entranceOutside().z(), c.minZ());
        assertEquals(1, c.sizeZ(), "clearanceOutside=1 → 一格");
    }

    @Test
    void clearanceBoxFollowsTheRotatedFacingNotTheDeclaredOne() {
        // ★ 2026-10-09 缺陷：净空条原先拿旋转前的 entranceFacing 算 → 判在错的一侧
        FacilityTemplate pen = pen();   // 声明朝北
        PlacementMath.PlacementPlan r0 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 0);
        PlacementMath.PlacementPlan r90 = PlacementMath.resolve(plan(), CellKey.of(0, 0), pen, 1);

        BlockBox c0 = PlacementMath.clearanceBox(r0, pen);
        assertEquals(1, c0.sizeZ());
        assertEquals(r0.entranceOutside().z(), c0.minZ());
        assertTrue(c0.minZ() < r0.entranceInside().z(), "净空必须在门的朝外一侧");

        BlockBox c90 = PlacementMath.clearanceBox(r90, pen);
        // 顺时针 90°：北 → 东。净空条必须沿 +X 延伸，而不是继续沿 Z。
        assertEquals(1, c90.sizeZ(), "旋转后净空不该还在 Z 方向铺开");
        assertEquals(1, c90.sizeX(), "clearanceOutside=1 仍是单格");
        assertEquals(r90.entranceOutside().x(), c90.minX());
        assertTrue(c90.minX() > r90.entranceInside().x(), "旋转后净空必须在东侧（+X）");
    }

    @Test
    void basePointLandsAtAnchorPlusBaseLocal() {
        // ★ 用户 2026-10-10：每个蓝图带一个「底座坐标标记」。底座基准点世界坐标 = 锚点 + 旋转后的 baseLocal，
        //   Y = 模块最底层（蓝图锚点 Y）。place/catalog 靠它说清"模块站哪、合并到哪层地基"。
        FacilityTemplate pen = pen();   // baseLocal=(3,3)（底座正中）
        PlacementMath.PlacementPlan p = PlacementMath.resolve(plan(), CellKey.of(1, 1), pen, 0);
        DimAnchor b = PlacementMath.basePoint(p, pen);
        assertEquals(p.anchor().x() + pen.baseLocal().x(), b.x());
        assertEquals(p.anchor().z() + pen.baseLocal().z(), b.z());
        assertEquals(p.anchor().y(), b.y(), "底座基准点 Y = 模块最底层");
    }
}
