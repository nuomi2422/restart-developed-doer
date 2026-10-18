package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.accept.WorldProbe;
import com.dwinovo.numen.settlement.core.accept.EntityView;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 施工收口的世界侧判据：任务说"结束"不等于"建好了"，答案只能从世界读。 */
class CompletionCheckerTest {

    private static final String DIM = TestData.DIM;

    /** 可写探针：key = "x,y,z"。 */
    private static final class FakeProbe implements WorldProbe {
        final Map<String, String> blocks = new HashMap<>();
        boolean loaded = true;

        void put(int x, int y, int z, String id) {
            blocks.put(x + "," + y + "," + z, id);
        }

        @Override public boolean loaded(String dimension, int x, int y, int z) {
            return loaded;
        }

        @Override public String blockIdAt(String dimension, int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:air");
        }

        @Override public List<EntityView> entities(String dimension, BlockBox box) {
            return List.of();
        }
    }

    @Test
    void allMarksPresentIsComplete() {
        FakeProbe probe = new FakeProbe();
        // 锚点 (10,64,20)：标记在局部 (0,0,0) 与 (1,0,0)
        probe.put(10, 64, 20, "minecraft:oak_fence");
        probe.put(11, 64, 20, "minecraft:oak_fence");
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"),
                new CompletionChecker.Mark(1, 0, 0, "minecraft:oak_fence"));

        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertTrue(r.complete());
        assertEquals(2, r.matched());
        assertTrue(r.missing().isEmpty());
    }

    @Test
    void missingCellIsReportedWithItsWorldPosition() {
        FakeProbe probe = new FakeProbe();
        probe.put(10, 64, 20, "minecraft:oak_fence");
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"),
                new CompletionChecker.Mark(1, 0, 0, "minecraft:oak_fence"));

        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertFalse(r.complete());
        assertEquals(1, r.missingCount());
        assertTrue(r.missing().get(0).startsWith("11,64,20"), r.missing().toString());
    }

    @Test
    void wrongBlockCountsAsMissingNotPresent() {
        FakeProbe probe = new FakeProbe();
        probe.put(10, 64, 20, "minecraft:oak_fence");
        probe.put(11, 64, 20, "minecraft:oak_fence_gate");   // 该是栅栏
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"),
                new CompletionChecker.Mark(1, 0, 0, "minecraft:oak_fence"));
        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertFalse(r.complete());
        assertTrue(r.missing().get(0).contains("实为"), r.missing().toString());
    }

    @Test
    void unloadedChunkIsMissingNotSilentlyPassed() {
        FakeProbe probe = new FakeProbe();
        probe.loaded = false;
        List<CompletionChecker.Mark> marks = List.of(
                new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence"));
        CompletionChecker.Result r = CompletionChecker.check(marks,
                DimAnchor.of(DIM, 10, 64, 20), 5, 5, 0, probe);
        assertFalse(r.complete(), "未加载不许当成功");
        assertTrue(r.missing().get(0).contains("未加载"));
    }

    @Test
    void rotationMovesMarkedCells() {
        // 局部 (0,0) 在 5×5 里顺时针 90° → (4,0)
        DimAnchor anchor = DimAnchor.of(DIM, 10, 64, 20);
        CompletionChecker.Mark m = new CompletionChecker.Mark(0, 0, 0, "minecraft:oak_fence");
        DimAnchor w = CompletionChecker.toWorld(m, anchor, 5, 5, 1);
        assertEquals(14, w.x());
        assertEquals(20, w.z());
    }

    @Test
    void penTemplateMarksMatchItsRealGeometry() {
        // V2：羊圈 7×7，标记必须落在它自己的外框上（角/边中点/门），不能凭空写
        FacilityTemplate pen = TemplateCatalog.byId("pen_sheep").orElseThrow();
        assertFalse(pen.marks().isEmpty(), "羊圈要有结构标记");
        for (CompletionChecker.Mark m : pen.marks()) {
            assertTrue(m.localX() >= 0 && m.localX() <= 6 && m.localZ() >= 0 && m.localZ() <= 6,
                    "标记必须在 7×7 内: " + m);
            boolean onPerimeter = m.localX() == 0 || m.localZ() == 0
                    || m.localX() == 6 || m.localZ() == 6;
            assertTrue(onPerimeter, "羊圈标记必须在围栏上: " + m);
        }
        assertEquals(1, pen.marks().stream().filter(m -> m.blockId().contains("gate")).count(),
                "恰好一处门");
    }

    @Test
    void tradeTemplateMarksIncludeBeds() {
        // 交易所的全部意义是床——床必须进标记（谎报教训：采样漏掉人在乎的东西=假完成）
        FacilityTemplate trade = TemplateCatalog.byId("trade_post").orElseThrow();
        long beds = trade.marks().stream().filter(m -> m.blockId().contains("bed")).count();
        assertEquals(4, beds, "四张床都要进标记");
        // 两层围墙：四角应有 y=0 与 y=1 两个栅栏标记
        long corners = trade.marks().stream()
                .filter(m -> m.blockId().equals("minecraft:oak_fence")).count();
        assertEquals(8, corners, "四角×两层");
    }

    @Test
    void emptyMarksIsNotComplete() {
        FakeProbe probe = new FakeProbe();
        CompletionChecker.Result r = CompletionChecker.check(List.of(),
                DimAnchor.of(DIM, 0, 0, 0), 5, 5, 0, probe);
        assertFalse(r.complete(), "没有标记就不许判完成");
        assertEquals(0, r.total());
    }

    @Test
    void houseMarksIncludeFurnitureSoAMissingChestIsCaught() {
        // ★ 2026-10-09 实机抓到的谎报：D3 核心屋报「世界核对 11/11 COMPLETE」，
        //   而箱子根本没落地——因为标记只采了结构，家具不在采样里。
        //   用户要的是"还差什么"，家具漏了就是谎报完成。
        var house = TemplateCatalog.byId("core_house").orElseThrow();
        long furniture = house.marks().stream()
                .filter(m -> m.blockId().contains("chest") || m.blockId().contains("furnace")
                        || m.blockId().contains("bed") || m.blockId().contains("crafting_table"))
                .count();
        assertEquals(4, furniture, "四件家具都必须进标记: " + house.marks());

        // 而且缺箱子时必须判"未完成"
        FakeProbe probe = new FakeProbe();
        int ax = 100;
        int ay = 64;
        int az = 200;
        for (CompletionChecker.Mark m : house.marks()) {
            String block = m.blockId();
            if (block.contains("chest")) {
                continue;   // 故意不摆箱子
            }
            // 按标记的世界坐标摆上（旋转 0 时 = 锚点 + 局部）
            probe.put(ax + m.localX(), ay + m.localY(), az + m.localZ(), block);
        }
        CompletionChecker.Result r = CompletionChecker.check(house.marks(),
                DimAnchor.of(DIM, ax, ay, az), house.sizeX(), house.sizeZ(), 0, probe);
        assertFalse(r.complete(), "缺箱子必须判未完成");
        assertTrue(r.missing().stream().anyMatch(s -> s.contains("chest")),
                "缺失项要点名箱子: " + r.missing());
    }

    @Test
    void platformMarksSampleTopSurfaceAndCatchUnleveledGround() {
        // ★ 2026-10-09 晚修：平台原来 marks=[] → inspect 跳过世界核对 → 施工账永远停在 BUILDING。
        //   现在平台必须有一组"顶面采样点是圆石"的标记，才能在场上判出"表面多是泥土"。
        FacilityTemplate platform = TemplateCatalog.byId("platform_cobble14").orElseThrow();
        assertFalse(platform.marks().isEmpty(), "平台必须有顶面采样标记");
        for (CompletionChecker.Mark m : platform.marks()) {
            assertEquals(6, m.localY(), "圆石顶面在局部 y=6（anchorYOffset=-6，y=0..5 是往下填实层）: " + m);
            assertTrue(m.localX() >= 0 && m.localX() <= 13 && m.localZ() >= 0 && m.localZ() <= 13,
                    "标记必须在 14×14 内: " + m);
            assertEquals("minecraft:cobblestone", m.blockId());
        }
        // 四角必须采到（"建了一半"最先缺的地方）
        assertTrue(platform.marks().stream().anyMatch(m -> m.localX() == 0 && m.localZ() == 0));
        assertTrue(platform.marks().stream().anyMatch(m -> m.localX() == 13 && m.localZ() == 13));

        // 锚点 y = floorY-6（anchorYOffset=-6）→ 顶面世界 y = 锚点 y + 6
        int ax = 100;
        int ay = 60;         // = floorY-6，floorY=66
        int az = 200;
        DimAnchor anchor = DimAnchor.of(DIM, ax, ay, az);

        FakeProbe good = new FakeProbe();
        for (CompletionChecker.Mark m : platform.marks()) {
            good.put(ax + m.localX(), ay + m.localY(), az + m.localZ(), "minecraft:cobblestone");
        }
        assertTrue(CompletionChecker.check(platform.marks(), anchor, 14, 14, 0, good).complete(),
                "顶面全是圆石 → 收口");

        // 真实实况：表面多是泥土、只一角圆石 → 必须判未完成，并点名还差的坐标
        FakeProbe dirt = new FakeProbe();
        for (CompletionChecker.Mark m : platform.marks()) {
            boolean keep = m.localX() == 0 && m.localZ() == 0;   // 只剩一角是圆石
            dirt.put(ax + m.localX(), ay + m.localY(), az + m.localZ(),
                    keep ? "minecraft:cobblestone" : "minecraft:dirt");
        }
        CompletionChecker.Result r = CompletionChecker.check(platform.marks(), anchor, 14, 14, 0, dirt);
        assertFalse(r.complete(), "地皮没铺完不能判收口");
        assertEquals(1, r.matched());
        assertTrue(r.missing().stream().anyMatch(s -> s.contains("cobblestone")),
                "缺失项要点名圆石: " + r.missing());
    }
}
