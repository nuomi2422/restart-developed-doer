package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** P3 恢复策略：出口 → 动作 + 步骤骨架。 */
class RecoveryPolicyTest {

    private static RecoveryDecision d(RecoveryOutcome o) {
        return new RecoveryDecision(o, "reason", true);
    }

    @Test void recoverGoesToBaseAndEquips() {
        RecoveryPlan p = RecoveryPolicy.plan(d(RecoveryOutcome.RECOVER), List.of());
        assertEquals(RecoveryAction.GOTO_BASE_AND_EQUIP, p.action());
        assertTrue(p.steps().get(0).contains("回基地"));
    }

    @Test void repairWithGapsPreparesAssets() {
        RecoveryPlan p = RecoveryPolicy.plan(d(RecoveryOutcome.REPAIR),
                List.of("minecraft:diamond_chestplate need 2 have 0"));
        assertEquals(RecoveryAction.PREPARE_MISSING_ASSETS, p.action());
        assertTrue(p.steps().stream().anyMatch(s -> s.contains("diamond_chestplate")));
    }

    @Test void repairWithoutGapsRetriesWithNewStrategy() {
        RecoveryPlan p = RecoveryPolicy.plan(d(RecoveryOutcome.REPAIR), List.of());
        assertEquals(RecoveryAction.RETRY_WITH_NEW_STRATEGY, p.action());
    }

    @Test void replanAndSelfCompileAndFallback() {
        assertEquals(RecoveryAction.REQUEST_REPLAN, RecoveryPolicy.plan(d(RecoveryOutcome.REPLAN), List.of()).action());
        assertEquals(RecoveryAction.REQUEST_SELF_COMPILE, RecoveryPolicy.plan(d(RecoveryOutcome.SELF_COMPILE), List.of()).action());
        assertEquals(RecoveryAction.PARK, RecoveryPolicy.plan(null, List.of()).action());
    }
}
