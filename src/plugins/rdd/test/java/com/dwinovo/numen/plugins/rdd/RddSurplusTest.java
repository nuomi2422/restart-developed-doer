package com.dwinovo.numen.plugins.rdd;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RddSurplusTest {
    @Test void timeDistanceHealthAndExtraCountEachEndBonus() {
        assertTrue(RddSurplusPolicy.withinBudget(29_000_000_000L, 36, 20, 7));
        assertFalse(RddSurplusPolicy.withinBudget(30_000_000_000L, 36, 20, 7));
        assertFalse(RddSurplusPolicy.withinBudget(1, 65, 20, 0));
        assertFalse(RddSurplusPolicy.withinBudget(1, 0, 12, 0));
        assertFalse(RddSurplusPolicy.withinBudget(1, 0, 20, 8));
    }
}
