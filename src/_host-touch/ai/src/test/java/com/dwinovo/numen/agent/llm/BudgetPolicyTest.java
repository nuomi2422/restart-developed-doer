package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预算策略（E1）的离线回归：角色映射、窗口计量与滑动过期、
 * 建议只在超限且过冷却时发出、配置损坏/缺失退回默认、enforce 拦截求值与通知限频、
 * shadow 永不拦截、0 = 不限、示例文件不覆盖。
 */
class BudgetPolicyTest {

    @TempDir
    Path dir;

    private long now = 1_700_000_000_000L;

    @AfterEach
    void tearDown() {
        BudgetPolicy.resetForTest();
    }

    /** 写配置（null = 不写）→ 重置 → 注入时钟 → install。 */
    private void installWithClock(String json) throws Exception {
        if (json != null) {
            Files.writeString(dir.resolve("budget-policy.json"), json, StandardCharsets.UTF_8);
        }
        BudgetPolicy.resetForTest();
        BudgetPolicy.setClockForTest(() -> now);
        BudgetPolicy.install(dir);
    }

    @Test
    void mapsPhasesToRoles() {
        assertEquals("execution", BudgetPolicy.roleOf("execution"));
        assertEquals("execution", BudgetPolicy.roleOf("execution_retry"));
        assertEquals("execution", BudgetPolicy.roleOf("goal_judging"));
        assertEquals("execution", BudgetPolicy.roleOf("compaction"));
        assertEquals("planning", BudgetPolicy.roleOf("stage_a"));
        assertEquals("planning", BudgetPolicy.roleOf("stage_b"));
        assertEquals("planning", BudgetPolicy.roleOf("fallback"));
        assertEquals("learning", BudgetPolicy.roleOf("review"));
        assertEquals("unknown", BudgetPolicy.roleOf("something_new"));
        assertEquals("unknown", BudgetPolicy.roleOf(null));
    }

    @Test
    void missingFileMeansUnlimitedAndWritesExample() throws Exception {
        installWithClock(null);
        assertTrue(Files.exists(dir.resolve("budget-policy.example.json")),
                "缺配置时应写一份示例供参考");
        BudgetPolicy.record("execution", 10_000_000, 60_000);
        assertNull(BudgetPolicy.advice("execution", 10_000_000), "默认策略不建策");
        assertEquals(0, BudgetPolicy.policyForTest().version());
    }

    @Test
    void advisesWhenProjectedTokensExceedLimitAndRespectsCooldown() throws Exception {
        installWithClock("""
                { "version": 3, "mode": "shadow", "windowMinutes": 60, "adviceCooldownMinutes": 5,
                  "roles": { "execution": { "tokensPerWindow": 1000, "callsPerWindow": 0, "minutesPerWindow": 0 } } }
                """);
        assertEquals(3, BudgetPolicy.policyForTest().version());
        BudgetPolicy.record("execution", 900, 1000);
        BudgetPolicy.Advice a = BudgetPolicy.advice("execution", 200);
        assertNotNull(a);
        assertEquals("execution", a.role());
        assertEquals(List.of("tokens"), a.exceeded());
        assertEquals(900, a.usedTokens());
        assertEquals(1100, a.projectedTokens());
        assertEquals("shadow", a.mode());
        assertEquals(1, a.used().calls());
        assertNull(BudgetPolicy.advice("execution", 200), "冷却期内不重复建议");
        now += 6 * 60_000L;
        assertNotNull(BudgetPolicy.advice("execution", 200), "冷却过后再建议");
        assertNull(BudgetPolicy.advice("planning", 999_999), "其他角色没限额 → 不建策");
    }

    @Test
    void windowPrunesOldCalls() throws Exception {
        installWithClock("""
                { "roles": { "execution": { "tokensPerWindow": 1000 } } }
                """);
        BudgetPolicy.record("execution", 900, 1000);
        assertEquals(1, BudgetPolicy.windowUsage("execution").calls());
        now += 61 * 60_000L;
        assertEquals(0, BudgetPolicy.windowUsage("execution").calls(), "超过 60 分钟窗口应过期");
        assertNull(BudgetPolicy.advice("execution", 500));
    }

    @Test
    void corruptFileFallsBackToDefaults() throws Exception {
        installWithClock("{ this is not json ");
        BudgetPolicy.record("execution", 10_000_000, 60_000);
        assertNull(BudgetPolicy.advice("execution", 10_000_000));
    }

    @Test
    void enforceBlocksWhenExceededAndIsPure() throws Exception {
        installWithClock("""
                { "mode": "enforce", "roles": { "execution": { "tokensPerWindow": 1000 } } }
                """);
        assertEquals("enforce", BudgetPolicy.policyForTest().mode());
        BudgetPolicy.record("execution", 900, 1000);
        BudgetPolicy.Advice b = BudgetPolicy.checkBlock("execution", 200);
        assertNotNull(b);
        assertEquals("execution", b.role());
        assertEquals(List.of("tokens"), b.exceeded());
        assertEquals("enforce", b.mode());
        assertNotNull(BudgetPolicy.checkBlock("execution", 200), "checkBlock 是纯求值，不受冷却限制");
        assertNull(BudgetPolicy.checkBlock("execution", 50), "预估后仍在上限内 → 放行");
        assertNull(BudgetPolicy.checkBlock("planning", 999_999), "没限额的角色不拦");
    }

    @Test
    void enforceSuppressesAdviceAndNotifyRespectsCooldown() throws Exception {
        installWithClock("""
                { "mode": "enforce", "adviceCooldownMinutes": 5,
                  "roles": { "learning": { "callsPerWindow": 1 } } }
                """);
        BudgetPolicy.record("review", 0, 1000);
        assertNull(BudgetPolicy.advice("review", 0), "enforce 模式不发 shadow 建议");
        BudgetPolicy.Advice b = BudgetPolicy.checkBlock("review", 0);
        assertNotNull(b);
        assertTrue(b.exceeded().contains("calls"));
        assertTrue(BudgetPolicy.notifyBlock(b), "首次拦截通知");
        assertFalse(BudgetPolicy.notifyBlock(b), "冷却内不重复通知");
        now += 6 * 60_000L;
        assertTrue(BudgetPolicy.notifyBlock(b), "冷却过后可再通知");
    }

    @Test
    void shadowModeNeverBlocks() throws Exception {
        installWithClock("""
                { "mode": "shadow", "roles": { "execution": { "tokensPerWindow": 1000 } } }
                """);
        BudgetPolicy.record("execution", 5000, 1000);
        assertNull(BudgetPolicy.checkBlock("execution", 0), "shadow 模式永不拦截");
    }

    @Test
    void unknownModeFallsBackToShadow() throws Exception {
        installWithClock("""
                { "mode": "yolo", "roles": { "execution": { "tokensPerWindow": 1000 } } }
                """);
        assertEquals("shadow", BudgetPolicy.policyForTest().mode());
        BudgetPolicy.record("execution", 5000, 1000);
        assertNull(BudgetPolicy.checkBlock("execution", 0));
    }

    @Test
    void minutesLimitWorks() throws Exception {
        installWithClock("""
                { "roles": { "planning": { "minutesPerWindow": 1 } } }
                """);
        BudgetPolicy.record("stage_a", 0, 61_000);
        BudgetPolicy.Advice a = BudgetPolicy.advice("stage_a", 0);
        assertNotNull(a);
        assertTrue(a.exceeded().contains("minutes"));
    }

    @Test
    void zeroMeansUnlimited() throws Exception {
        installWithClock("""
                { "roles": { "execution": { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 } } }
                """);
        BudgetPolicy.record("execution", 5_000_000, 600_000);
        assertNull(BudgetPolicy.advice("execution", 5_000_000));
    }

    @Test
    void exampleIsNotOverwritten() throws Exception {
        Files.writeString(dir.resolve("budget-policy.example.json"), "KEEP", StandardCharsets.UTF_8);
        installWithClock(null);
        assertEquals("KEEP", Files.readString(dir.resolve("budget-policy.example.json")));
    }
}
