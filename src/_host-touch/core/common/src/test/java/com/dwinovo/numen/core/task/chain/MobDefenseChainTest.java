package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDefenseChainTest {

    @Test
    void reflexFightHasAStrictBoundedOwnershipWindow() {
        long started = 4_000L;
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS - 1));
        assertTrue(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS));
    }

    @Test
    void unstartedOrFutureFightNeverTimesOut() {
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(Long.MIN_VALUE, 10_000L));
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(10_000L, 9_999L));
    }
}
