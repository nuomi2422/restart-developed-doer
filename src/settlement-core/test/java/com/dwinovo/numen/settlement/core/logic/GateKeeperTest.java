package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.accept.EntityView;
import com.dwinovo.numen.settlement.core.model.BlockBox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 过门自动关门：三条安全约束（只关不开 / 门洞有人不关 / 未加载不动手）。 */
class GateKeeperTest {

    @Test
    void closesAnOpenGateWhenDoorwayIsClear() {
        GateKeeper.Decision d = GateKeeper.decide(true, true, true, false);
        assertTrue(d.close(), "敞着的门、门洞空了 → 该关");
    }

    @Test
    void neverOpensAClosedGate() {
        // 只关门、不开门：开门是同伴自己的路权判断
        GateKeeper.Decision d = GateKeeper.decide(true, true, false, false);
        assertFalse(d.close(), "门已经关着就不动");
        assertEquals("门本来就是关的", d.reason());
    }

    @Test
    void refusesToCloseWhileSomebodyStandsInTheDoorway() {
        // 关门＝把方块塞进她身体里，宁可多等一刻
        GateKeeper.Decision d = GateKeeper.decide(true, true, true, true);
        assertFalse(d.close(), "门洞里有人不许关");
        assertTrue(d.reason().contains("门洞"), d.reason());
    }

    @Test
    void doesNothingWhenChunkIsNotLoaded() {
        GateKeeper.Decision d = GateKeeper.decide(false, true, true, false);
        assertFalse(d.close(), "未加载不许猜（也别说'本来就是关的'）");
        assertTrue(d.reason().contains("未加载"), d.reason());
    }

    @Test
    void ignoresNonGateBlocks() {
        assertFalse(GateKeeper.decide(true, false, true, false).close());
    }

    // ── 门洞占用判定 ────────────────────────────────────────────────

    @Test
    void entityInTheDoorwayCountsAsOccupied() {
        // 门在 (10,64,20)：中心 (10.5, 64, 20.5)
        List<EntityView> es = List.of(new EntityView("minecraft:sheep", 10.5, 64.0, 20.5));
        assertTrue(GateKeeper.doorwayOccupied(es, 10, 64, 20));
    }

    @Test
    void entityOnTopOfTheDoorwayAlsoCounts() {
        // 站在门格正上方（跳过门顶）也算占住——关了会卡住
        List<EntityView> es = List.of(new EntityView("minecraft:sheep", 10.5, 65.0, 20.5));
        assertTrue(GateKeeper.doorwayOccupied(es, 10, 64, 20));
    }

    @Test
    void entityBesideTheGateDoesNotBlock() {
        List<EntityView> es = List.of(new EntityView("minecraft:sheep", 13.5, 64.0, 20.5));
        assertFalse(GateKeeper.doorwayOccupied(es, 10, 64, 20), "两格外不算门洞");
    }

    @Test
    void entityFarBelowDoesNotBlock() {
        // 门下方 2.5 格（坑里）——身体跨度够不到门格，不该阻止关门
        // 判据是"身体跨度与门格相交"，不是"脚在哪一格"：门格 64 的保守下界是 62
        List<EntityView> es = List.of(new EntityView("minecraft:sheep", 10.5, 61.5, 20.5));
        assertFalse(GateKeeper.doorwayOccupied(es, 10, 64, 20));
    }

    @Test
    void entityStandingOnADirtPathInTheDoorwayStillBlocks() {
        // 2026-10-09 实机抓到的真缺陷：dirt_path 碰撞高只有 15/16，同伴的 y=66.9375
        // 比门格 68 低 1.06 格，身体却在门格里。早先的 y >= gateY-0.5（67.5）把它判成
        // "门洞空" → 把门关在了她身上。半砖/耕地/雪层/台阶上都会重现。
        List<EntityView> es = List.of(new EntityView("minecraft:player", 10.38, 66.9375, 19.53));
        assertTrue(GateKeeper.doorwayOccupied(es, 10, 68, 20),
                "★ 站在门口半格方块上（y 比门格低 1 格多）也必须算占住——否则关门会夹住她");
        // 并且整条判定必须因此拒绝关门
        assertFalse(GateKeeper.decide(true, true, true,
                GateKeeper.doorwayOccupied(es, 10, 68, 20)).close());
    }

    @Test
    void entityClearlyBelowTheDoorwayDoesNotBlock() {
        // 门格 68 的保守下界是 66（身高上界 2 格）：65.5 在门外
        List<EntityView> es = List.of(new EntityView("minecraft:player", 10.5, 65.5, 20.5));
        assertFalse(GateKeeper.doorwayOccupied(es, 10, 68, 20));
    }

    @Test
    void emptyOrNullEntityListIsClear() {
        assertFalse(GateKeeper.doorwayOccupied(List.of(), 10, 64, 20));
        assertFalse(GateKeeper.doorwayOccupied(null, 10, 64, 20));
    }

    @Test
    void doorwayBoxCoversGateAndTheCellAbove() {
        BlockBox b = GateKeeper.doorwayBox(10, 64, 20);
        assertTrue(b.contains(10, 64, 20));
        assertTrue(b.contains(10, 65, 20));
        assertEquals(2, b.sizeY());
        assertEquals(1, b.sizeX());
    }

    @Test
    void fullFlowCloseAfterPassingThrough() {
        // 同伴走过门（门开着、门洞有人）→ 不关；走开后 → 关
        List<EntityView> passing = List.of(new EntityView("minecraft:player", 10.5, 64.0, 20.5));
        assertFalse(GateKeeper.decide(true, true, true,
                GateKeeper.doorwayOccupied(passing, 10, 64, 20)).close());
        List<EntityView> gone = List.of(new EntityView("minecraft:player", 14.5, 64.0, 20.5));
        assertTrue(GateKeeper.decide(true, true, true,
                GateKeeper.doorwayOccupied(gone, 10, 64, 20)).close());
    }
}
