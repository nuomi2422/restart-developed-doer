package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RddExpansionPolicy 纯函数单测：懒展开失败恢复阈值(§9)，批B-4。 */
class RddExpansionPolicyTest {

    @Test void budgetExhaustsAtMaxAttempts() {
        assertFalse(RddExpansionPolicy.budgetExhausted(0));
        assertFalse(RddExpansionPolicy.budgetExhausted(RddExpansionPolicy.MAX_EXPAND_ATTEMPTS - 1));
        assertTrue(RddExpansionPolicy.budgetExhausted(RddExpansionPolicy.MAX_EXPAND_ATTEMPTS));
        assertTrue(RddExpansionPolicy.budgetExhausted(RddExpansionPolicy.MAX_EXPAND_ATTEMPTS + 1));
    }

    @Test void shouldFireWithinBudgetAfterCooldown() {
        long now = 100_000;
        // 干净状态：0 尝试、无冷却 → 该开火
        assertTrue(RddExpansionPolicy.shouldFire(false, false, 0, 0, now));
        // 预算内、冷却已过 → 开火（§9 重试 ≤3）
        assertTrue(RddExpansionPolicy.shouldFire(false, false, RddExpansionPolicy.MAX_EXPAND_ATTEMPTS - 1, 0, now));
        // 冷却未过 → 不开（防每 tick 猛开 LLM）
        assertFalse(RddExpansionPolicy.shouldFire(false, false, 1, now + RddExpansionPolicy.EXPAND_COOLDOWN_MS, now));
        // 刚好到冷却点 → 开
        assertTrue(RddExpansionPolicy.shouldFire(false, false, 1, now, now));
    }

    @Test void inFlightOrEscalatedNeverFires() {
        long now = 100_000;
        assertFalse(RddExpansionPolicy.shouldFire(true, false, 0, 0, now));
        assertFalse(RddExpansionPolicy.shouldFire(false, true, 0, 0, now));
        assertFalse(RddExpansionPolicy.shouldFire(true, true, 0, 0, now));
    }

    @Test void exhaustedBudgetNeverFiresEvenWithNoCooldown() {
        // 预算耗尽 = escalate 停靠：即便冷却已过也不再开（等主人 /goal 重来或清除）
        assertFalse(RddExpansionPolicy.shouldFire(false, false, RddExpansionPolicy.MAX_EXPAND_ATTEMPTS, 0, 100_000));
        // escalate 置位后更不开
        assertFalse(RddExpansionPolicy.shouldFire(false, true, RddExpansionPolicy.MAX_EXPAND_ATTEMPTS, 0, 100_000));
    }
}
