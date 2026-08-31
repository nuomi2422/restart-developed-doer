package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MutationBudgetTest {
    @Test
    void stopsAfterConfiguredAttempts() {
        MutationBudget budget = new MutationBudget(2, Duration.ofMinutes(1));
        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire());
        assertTrue(budget.exhausted());
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new MutationBudget(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new MutationBudget(1, Duration.ZERO));
    }
}
