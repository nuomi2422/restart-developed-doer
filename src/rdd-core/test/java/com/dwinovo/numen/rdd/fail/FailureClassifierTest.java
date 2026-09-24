package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** P2.0 失败诊断：代码先硬分类出口，未知保持未知。 */
class FailureClassifierTest {

    private static FailureEvent ev(FailureKind k) {
        return FailureEvent.of("s1", "p1", k, "observed");
    }

    @Test void softwareDefectGoesToSelfCompile() {
        assertEquals(RecoveryOutcome.SELF_COMPILE,
                FailureClassifier.classify(ev(FailureKind.SOFTWARE_DEFECT), null).outcome());
    }

    @Test void deathWithBackupAndBaseRecoversWithoutReplan() {
        var ctx = new FailureContext(true, true, false, false);
        RecoveryDecision d = FailureClassifier.classify(ev(FailureKind.DEATH), ctx);
        assertEquals(RecoveryOutcome.RECOVER, d.outcome());
        assertTrue(d.auto(), "RECOVER 应由代码直接执行（回基地取备用）");
    }

    @Test void deathWithoutBackupRepairsNotReplans() {
        var ctx = new FailureContext(false, false, false, false);
        assertEquals(RecoveryOutcome.REPAIR, FailureClassifier.classify(ev(FailureKind.DEATH), ctx).outcome());
    }

    @Test void unrecoverableTargetReplans() {
        var lost = new FailureContext(false, false, true, true);
        assertEquals(RecoveryOutcome.REPLAN, FailureClassifier.classify(ev(FailureKind.TARGET_LOST), lost).outcome());
        assertEquals(RecoveryOutcome.REPLAN, FailureClassifier.classify(ev(FailureKind.DEATH), lost).outcome());
    }

    @Test void resourceAndToolAndPathRepair() {
        var ctx = new FailureContext(false, false, false, false);
        assertEquals(RecoveryOutcome.REPAIR, FailureClassifier.classify(ev(FailureKind.RESOURCE_MISSING), ctx).outcome());
        assertEquals(RecoveryOutcome.REPAIR, FailureClassifier.classify(ev(FailureKind.PATH_BLOCKED), ctx).outcome());
        assertEquals(RecoveryOutcome.REPAIR, FailureClassifier.classify(ev(FailureKind.TOOL_ERROR), ctx).outcome());
    }

    @Test void unknownRepeatsToReplanElseRepair() {
        assertEquals(RecoveryOutcome.REPAIR,
                FailureClassifier.classify(ev(FailureKind.UNKNOWN), new FailureContext(false, false, false, false)).outcome());
        assertEquals(RecoveryOutcome.REPLAN,
                FailureClassifier.classify(ev(FailureKind.UNKNOWN), new FailureContext(false, false, true, false)).outcome());
    }
}
