package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** P2-E 时间/成本评分：种田等待不划算、村庄取现成划算。 */
class TaskCostModelTest {

    @Test void scoreFormula() {
        // 收益50 - 时间5 - 风险10 + 复用20 = 55
        assertEquals(55, TaskCostModel.score(50, 5, 10, 20));
    }

    @Test void negativeInputsClamped() {
        assertEquals(50, TaskCostModel.score(50, -5, -5, -5));
    }

    @Test void villagePickupBeatsFarming() {
        int pickup = TaskCostModel.score(TaskCostModel.Activity.PICKUP_EXISTING, 20, 0, 0);
        int farming = TaskCostModel.score(TaskCostModel.Activity.FARM_AND_WAIT, 20, 0, 0);
        assertTrue(pickup > farming, "拿现成食物应优于种田等待");
    }

    @Test void farmingAndWaitIsWastefulForSmallGain() {
        // 种田等待换少量食物、无复用 → 明显不划算
        assertTrue(TaskCostModel.isWasteful(TaskCostModel.Activity.FARM_AND_WAIT, 5, 0));
        // 但若未来复用很高（如大规模农场供长期食物），可能仍值
        assertFalse(TaskCostModel.isWasteful(TaskCostModel.Activity.FARM_AND_WAIT, 5, 50));
    }

    @Test void huntAndPickupAreCheap() {
        assertTrue(TaskCostModel.Activity.PICKUP_EXISTING.cost() < TaskCostModel.Activity.HUNT_ANIMAL.cost());
        assertTrue(TaskCostModel.Activity.HUNT_ANIMAL.cost() < TaskCostModel.Activity.FARM_AND_WAIT.cost());
        assertTrue(TaskCostModel.Activity.CRAFT.cost() < TaskCostModel.Activity.MINE_DEEP.cost());
    }

    @Test void explainAndCostsAreStable() {
        assertTrue(TaskCostModel.explain().contains("farm_and_wait=20"));
        assertEquals(7, TaskCostModel.costs().size());
        assertEquals(20, TaskCostModel.costs().get("FARM_AND_WAIT"));
    }
}
