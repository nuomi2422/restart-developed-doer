package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.rdd.api.SubtaskSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5：{@code asset_key} 的「持有 vs 累计」语义。
 *
 * <p>红线级约束：{@code mode} 缺省必须与本轮改动前<b>逐字一致</b>，
 * 否则每一级 {@code hold} 判定的历史行为都被改掉了（RL-6/RL-7 与全部既有回归都依赖它）。
 */
class HardCodedEvaluatorModeTest {

    // ---- 缺省行为逐字不变（红线保护） ----

    @Test
    void defaultModeIsHoldAndBehavesExactlyAsBefore() {
        Map<String, Object> cond = Map.of("asset_key", "minecraft:wheat_seeds", "minimum", 10);
        // 实机那个假完成的场景：背包天然 33 个 → hold 判达成（这正是要靠 acquire 修的）
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 33)));
        // 即使传了 baseline，hold 也**不读**它
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 33),
                Map.of("minecraft:wheat_seeds", 33)));
        // 未达阈值仍然 false（不得因为加了重载就放宽）
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 9)));
    }

    @Test
    void explicitHoldEqualsAbsentMode() {
        Map<String, Object> implicit = Map.of("asset_key", "minecraft:stone", "minimum", 3);
        Map<String, Object> explicit = Map.of("asset_key", "minecraft:stone", "minimum", 3,
                "mode", "hold");
        Map<String, Integer> empty = Map.of();
        Map<String, Integer> two = Map.of("minecraft:stone", 2);
        Map<String, Integer> three = Map.of("minecraft:stone", 3);
        Map<String, Integer> many = Map.of("minecraft:stone", 99);
        for (Map<String, Integer> counts : List.of(empty, two, three, many)) {
            assertEquals(HardCodedEvaluator.matches(implicit, counts),
                    HardCodedEvaluator.matches(explicit, counts),
                    "显式 hold 与缺省必须等价，counts=" + counts);
        }
    }

    // ---- acquire：本次二级期间的新增量 ----

    @Test
    void acquireIsUnsatisfiedWhenSheAlreadyHadThem() {
        Map<String, Object> cond = Map.of("asset_key", "minecraft:wheat_seeds", "minimum", 10,
                "mode", "acquire");
        // 假完成复现：背包 33 个、基线也是 33 → 新增 0 → 不得判达成
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 33),
                Map.of("minecraft:wheat_seeds", 33)));
        // 真的种出来 10 个 → 达成
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 43),
                Map.of("minecraft:wheat_seeds", 33)));
        // 只种了 9 个 → 未达成
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 42),
                Map.of("minecraft:wheat_seeds", 33)));
    }

    @Test
    void acquireWithoutBaselineIsNeverSatisfied() {
        Map<String, Object> cond = Map.of("asset_key", "minecraft:wheat_seeds", "minimum", 1,
                "mode", "acquire");
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 99), null),
                "没有基线就判未达成（宁可漏判，不许假完成）");
    }

    @Test
    void acquireIsNetDeltaSoConsumptionCountsAgainstIt() {
        Map<String, Object> cond = Map.of("asset_key", "minecraft:wheat_seeds", "minimum", 10,
                "mode", "acquire");
        // 基线 30、现在只剩 5 → 净增 -25 → 未达成。acquire 算的是**净增量**，
        // 所以"边做边吃"不会被算成收获；想要"不消耗"语义就该用 hold，两者职责不同。
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 5),
                Map.of("minecraft:wheat_seeds", 30)));
        // 净增刚好达标：30 → 40（中途吃掉过，但净增 10 仍算数）
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 40),
                Map.of("minecraft:wheat_seeds", 30)));
        assertFalse(HardCodedEvaluator.matches(cond, Map.of("minecraft:wheat_seeds", 39),
                Map.of("minecraft:wheat_seeds", 30)));
    }

    @Test
    void acquireWithZeroMinimumIsSatisfiedOnceBaselineExists() {
        Map<String, Object> cond = Map.of("asset_key", "minecraft:stone", "minimum", 0,
                "mode", "acquire");
        assertTrue(HardCodedEvaluator.matches(cond, Map.of(), Map.of()));
    }

    @Test
    void acquireMissingAssetKeyInBaselineCountsAsZeroBaseline() {
        Map<String, Object> cond = Map.of("asset_key", "minecraft:carrot", "minimum", 3,
                "mode", "acquire");
        assertTrue(HardCodedEvaluator.matches(cond, Map.of("minecraft:carrot", 3), Map.of()));
    }

    // ---- 畸形输入：显式拒绝，绝不静默降级 ----

    @Test
    void unknownModeIsRejectedNotSilentlyTreatedAsHold() {
        Map<String, Object> typo = Map.of("asset_key", "minecraft:stone", "minimum", 1,
                "mode", "acqurie");   // 拼错
        assertNull(HardCodedEvaluator.modeOf(typo));
        assertFalse(HardCodedEvaluator.matches(typo, Map.of("minecraft:stone", 99)),
                "拼错的 mode 不得退化成 hold —— 那正是要消灭的假完成");
    }

    @Test
    void nonStringOrBlankModeIsRejected() {
        Map<String, Object> numericMode = new LinkedHashMap<>();
        numericMode.put("asset_key", "minecraft:stone");
        numericMode.put("mode", 7);
        assertNull(HardCodedEvaluator.modeOf(numericMode));

        assertEquals(HardCodedEvaluator.MODE_HOLD,
                HardCodedEvaluator.modeOf(Map.of("asset_key", "a", "mode", "  ")));
        assertEquals(HardCodedEvaluator.MODE_HOLD, HardCodedEvaluator.modeOf(Map.of("asset_key", "a")));
        assertEquals(HardCodedEvaluator.MODE_HOLD, HardCodedEvaluator.modeOf(null));
    }

    @Test
    void groupConditionsIgnoreModeButStillWork() {
        Map<String, Object> food = Map.of("group", "food", "minimum", 3);
        assertTrue(HardCodedEvaluator.matches(food, Map.of("minecraft:bread", 3)));
        assertTrue(HardCodedEvaluator.matches(food, Map.of("minecraft:bread", 3), Map.of()));
    }

    @Test
    void negativeMinimumStillRejectedInBothModes() {
        assertFalse(HardCodedEvaluator.matches(
                Map.of("asset_key", "a", "minimum", -1, "mode", "acquire"),
                Map.of("a", 99), Map.of()));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "a", "minimum", -1),
                Map.of("a", 99)));
    }

    @Test
    void fractionalMinimumStillRejectedInBothModes() {
        assertFalse(HardCodedEvaluator.matches(
                Map.of("asset_key", "a", "minimum", 1.5, "mode", "acquire"),
                Map.of("a", 99), Map.of()));
    }

    @Test
    void nullConditionOrCountsStayFalse() {
        assertFalse(HardCodedEvaluator.matches(null, Map.of(), Map.of()));
        assertFalse(HardCodedEvaluator.matches(Map.of("asset_key", "a"), null, Map.of()));
    }

    // ---- 建链期就拒未知 mode（不让非法条件活到判定层） ----

    @Test
    void chainFactoryRejectsUnknownMode() {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("asset_key", "minecraft:wheat_seeds");
        condition.put("minimum", 10);
        condition.put("mode", "nope");
        List<SubtaskSpec> specs = List.of(new SubtaskSpec("collect seeds", condition, null));
        UUID companion = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromSpec(companion, "village setup", specs),
                "建链期必须拒绝未知 mode，而不是拖到判定层静默降级");
    }

    @Test
    void chainFactoryAcceptsHoldAndAcquire() {
        Map<String, Object> hold = Map.of("asset_key", "minecraft:stone", "minimum", 1);
        Map<String, Object> acquire = new LinkedHashMap<>();
        acquire.put("asset_key", "minecraft:wheat_seeds");
        acquire.put("minimum", 10);
        acquire.put("mode", "acquire");
        UUID companion = UUID.randomUUID();
        assertNotNull(RddChainFactory.fromSpec(companion, "s1",
                List.of(new SubtaskSpec("hold", hold, null))));
        assertNotNull(RddChainFactory.fromSpec(companion, "s2",
                List.of(new SubtaskSpec("acquire", acquire, null))));
    }
}