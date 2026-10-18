package com.dwinovo.numen.rdd.side;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 周期支线模板规格（只预留，本轮不实现农田）。 */
class RecurringSideTaskSpecTest {

    @Test
    void firesOnAnchorEveryIntervalGameDays() {
        RecurringSideTaskSpec spec = new RecurringSideTaskSpec(
                "farm", "farm_cycle", 2, 5, RecurringSideTaskSpec.MissedPolicy.SKIP, true);
        assertTrue(spec.dueOn(5));
        assertTrue(spec.dueOn(7));
        assertTrue(spec.dueOn(9));
        assertFalse(spec.dueOn(6));
        assertFalse(spec.dueOn(8));
        assertFalse(spec.dueOn(4), "锚点之前不触发");
    }

    @Test
    void disabledNeverFires() {
        RecurringSideTaskSpec spec = new RecurringSideTaskSpec(
                "farm", "farm_cycle", 2, 0, RecurringSideTaskSpec.MissedPolicy.SKIP, false);
        assertFalse(spec.dueOn(0));
        assertFalse(spec.dueOn(2));
    }
}
