package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** RddChainFactory.fromStages：Stage-A 一级清单 → 全未展开多一级链（纯 JVM，批B-1）。 */
class RddChainFactoryStagesTest {

    @Test void buildsAllStagesUnexpandedWithStableIds() {
        UUID c = UUID.randomUUID();
        Goal g = RddChainFactory.fromStages(c, "通关MC", List.of(
                new PrimarySpec("石器时代", List.of()),
                new PrimarySpec("铁器时代", List.of(new AssetRequirement("minecraft:stone_pickaxe", 1))),
                new PrimarySpec("下界", List.of(new AssetRequirement("minecraft:flint_and_steel", 1)))));
        assertEquals("goal-" + c.toString().substring(0, 8), g.id());
        assertEquals("通关MC", g.description());
        assertEquals(3, g.primaryGoals().size());
        for (int i = 0; i < 3; i++) {
            PrimaryGoal p = g.primaryGoals().get(i);
            assertTrue(p.unexpanded(), "stage " + i + " must start unexpanded");
            assertEquals(0, p.subtasks().size());
            assertTrue(p.id().startsWith("primary-" + c.toString().substring(0, 8) + "-"));
        }
        PrimaryGoal p2 = g.primaryGoals().get(1);
        assertEquals("铁器时代", p2.description());
        assertEquals(1, p2.waitFor().size());
        assertEquals("minecraft:stone_pickaxe", p2.waitFor().get(0).assetKey());
        assertEquals(1, p2.waitFor().get(0).minimum());
        // 各一级 id 互异
        assertNotEquals(g.primaryGoals().get(0).id(), g.primaryGoals().get(1).id());
        // 一级前缀 primary-… 与已展开链的二级前缀 subtask-… 不冲突（懒注入全局唯一）
        assertFalse(g.primaryGoals().get(0).id().startsWith("subtask-"));
    }

    @Test void rejectsBlankOrTooManyStages() {
        UUID c = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromStages(c, "x", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromStages(c, "", List.of(new PrimarySpec("a", List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromStages(null, "x", List.of(new PrimarySpec("a", List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromStages(c, "x", List.of(new PrimarySpec("  ", List.of()))));
        List<PrimarySpec> many = new ArrayList<>();
        for (int i = 0; i < RddChainFactory.MAX_STAGES + 1; i++) {
            many.add(new PrimarySpec("s" + i, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> RddChainFactory.fromStages(c, "x", many));
    }

    @Test void fromStagesDrivesLazyChainAcrossStageBoundaries() {
        UUID c = UUID.randomUUID();
        TaskChain chain = new TaskChain(RddChainFactory.fromStages(c, "通关", List.of(
                new PrimarySpec("石器时代", List.of()),
                new PrimarySpec("铁器时代", List.of()))));
        String p1 = chain.currentPrimary().id();
        assertEquals("石器时代", chain.currentPrimary().description());
        // 首级未展开：诚实无当前二级、拒绝激活/启动（不误置 WAITING）
        assertTrue(chain.currentPrimary().unexpanded());
        assertNull(chain.currentSubtask());
        assertThrows(IllegalStateException.class, () -> chain.activateCurrent(Map.of("minecraft:stone", 1)));
        assertThrows(IllegalStateException.class, chain::startCurrent);
        // 宿主懒注入 p1 的二级（id 以该级 id 为前缀 → 全局唯一）
        String s1 = p1 + "-s0";
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded(s1, "收石头",
                Map.of("asset_key", "minecraft:stone", "minimum", 1))));
        assertFalse(chain.currentPrimary().unexpanded());
        assertTrue(chain.activateCurrent(Map.of("minecraft:stone", 1)));
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        assertTrue(chain.applyHardCodedResult(s1, true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        // Supervisor 确认 p1 → 推到下一级，下一级又是未展开 → 懒边界成立
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, p1, "done"));
        assertEquals("铁器时代", chain.currentPrimary().description());
        assertTrue(chain.currentPrimary().unexpanded());
        assertNull(chain.currentSubtask());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
    }
}
