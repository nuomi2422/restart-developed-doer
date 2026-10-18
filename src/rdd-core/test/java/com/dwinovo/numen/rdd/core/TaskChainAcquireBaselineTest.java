package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code acquire} 基线随链持久化（2026-09-30 F5）。
 *
 * <p>为什么这条单测值钱：代次 {@code planRevision} 已经吃过一次"只在宿主内存里 →
 * 重启后撞号"的亏。这里守的是同一类洞 —— <b>运行时事实必须活得过重启</b>。
 */
class TaskChainAcquireBaselineTest {

    private static Goal goal() {
        Map<String, Object> acquire = new java.util.LinkedHashMap<>();
        acquire.put("asset_key", "minecraft:wheat_seeds");
        acquire.put("minimum", 10);
        acquire.put("mode", "acquire");
        return new Goal("g", "goal",
                List.of(new PrimaryGoal("p0", "stage",
                        List.of(Subtask.hardCoded("p0-r1-0", "collect seeds", acquire)))));
    }

    private static TaskChain activeChain() {
        TaskChain chain = new TaskChain(goal());
        chain.activateCurrent(Map.of());
        return chain;
    }

    /** 从 PENDING 直接 startCurrent（activateCurrent 已把二级置 RUNNING，不能再 start）。 */
    private static TaskChain runningChain() {
        TaskChain chain = new TaskChain(goal());
        chain.startCurrent();
        return chain;
    }

    @Test
    void baselineIsCapturedAndReadBack() {
        TaskChain chain = activeChain();
        assertNull(chain.acquireBaseline("p0-r1-0"), "捕获前没有基线");

        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));
        assertEquals(Map.of("minecraft:wheat_seeds", 33), chain.acquireBaseline("p0-r1-0"));

        // 二级条件现在真的能判了
        assertFalse(HardCodedEvaluator.matches(chain.currentSubtask().condition(),
                Map.of("minecraft:wheat_seeds", 33), chain.acquireBaseline("p0-r1-0")));
        assertTrue(HardCodedEvaluator.matches(chain.currentSubtask().condition(),
                Map.of("minecraft:wheat_seeds", 43), chain.acquireBaseline("p0-r1-0")));
    }

    @Test
    void baselineSurvivesRestartThroughJson() {
        TaskChain chain = activeChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));

        TaskChain restored = TaskChain.fromJson(chain.toJson());
        assertEquals(Map.of("minecraft:wheat_seeds", 33), restored.acquireBaseline("p0-r1-0"),
                "重启后基线必须还在，否则已完成的 acquire 二级会重新变成未达成");
    }

    @Test
    void startCurrentWithCountsCapturesBaselineSoTheNeverSatisfiedGuardIsNeverHit() {
        // 2026-10-02 事故：rdd_submit 走的是 startCurrent()（不拍基线），
        // 于是 mode=acquire 恒判未达成 → AI 做出东西被判失败 → 被看门狗逼着重做一遍。
        // 修法是在这条路上也拍基线（TaskChain.startCurrentWithCounts）。
        // 这条单测守住「从 PENDING 直接开跑也必须有基线」——
        // 没有它，HardCodedEvaluator 的「无基线 = 判未达成」守卫会在正常路径上被触发。
        TaskChain chain = new TaskChain(goal());   // 二级还是 PENDING，能 start
        assertNull(chain.acquireBaseline("p0-r1-0"), "开跑前不该有基线");

        chain.startCurrentWithCounts(Map.of("minecraft:wheat_seeds", 33, "minecraft:stone", 5));

        assertEquals(Map.of("minecraft:wheat_seeds", 33, "minecraft:stone", 5),
                chain.acquireBaseline("p0-r1-0"), "startCurrentWithCounts 必须把基线拍下来");
        // 有了基线，acquire 就能正常判：净增 10 才算达成
        assertFalse(HardCodedEvaluator.matches(chain.currentSubtask().condition(),
                Map.of("minecraft:wheat_seeds", 33), chain.acquireBaseline("p0-r1-0")),
                "净增 0 不算达成");
        assertTrue(HardCodedEvaluator.matches(chain.currentSubtask().condition(),
                Map.of("minecraft:wheat_seeds", 43), chain.acquireBaseline("p0-r1-0")),
                "净增 10 算达成");
    }

    @Test
    void captureAcquireBaselineKeepsTheFirstSnapshot() {
        // activateCurrent / activateCurrentWithSnapshot / startCurrentWithCounts 可能对同一个
        // 子任务先后被调到。基线只能拍一次：后到的不能覆盖，
        // 否则「接活那一刻她有多少」这个事实就丢了，acquire 会拿更晚的量当基线、净增算错。
        TaskChain chain = runningChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 99));
        assertEquals(Map.of("minecraft:wheat_seeds", 33), chain.acquireBaseline("p0-r1-0"),
                "基线必须是接活那一刻的量，后来的快照不得覆盖");
    }

    @Test
    void oldSaveWithoutBaselineKeyLoadsAsNoBaseline() {
        TaskChain chain = activeChain();
        String json = chain.toJson();
        // 模拟本轮之前写出的存档：删掉新键
        String legacy = json.replaceAll(",\"acquireBaselines\":\\{[^}]*\\}", "");
        assertFalse(legacy.contains("acquireBaselines"));

        TaskChain restored = TaskChain.fromJson(legacy);
        assertNull(restored.acquireBaseline("p0-r1-0"), "旧存档缺键 = 没有基线，不算损坏");
        // 后果必须是"判未达成"而不是"判达成"
        assertFalse(HardCodedEvaluator.matches(restored.currentSubtask().condition(),
                Map.of("minecraft:wheat_seeds", 99), restored.acquireBaseline("p0-r1-0")));
    }

    @Test
    void captureIsIdempotentSoRetryDoesNotMoveTheGoalpost() {
        TaskChain chain = activeChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));
        // 重试时若把基线覆盖成 43，acquire 就要再拿 10 个 = 白做
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 43));
        assertEquals(Map.of("minecraft:wheat_seeds", 33), chain.acquireBaseline("p0-r1-0"),
                "已有基线不得被覆盖");
    }

    @Test
    void baselineIsDefensivelyCopied() {
        TaskChain chain = activeChain();
        Map<String, Integer> mutable = new LinkedHashMapSource().map();
        chain.captureAcquireBaseline("p0-r1-0", mutable);
        mutable.put("minecraft:wheat_seeds", 999);
        assertEquals(33, chain.acquireBaseline("p0-r1-0").get("minecraft:wheat_seeds"),
                "捕获后不可变：调用方改自己的 map 不得改写基线");
    }

    @Test
    void replanningForgetsBaselineOfReusedSubtasks() {
        TaskChain chain = runningChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));

        chain.markFailed("p0-r1-0", "dead end");
        chain.enterReplanningFromStuck("stuck");
        chain.resumeFromReplanning();

        assertNull(chain.acquireBaseline("p0-r1-0"),
                "重跑必须清掉上一轮基线，否则 acquire 永远判未达成（比假完成更难查）");
    }

    @Test
    void replacingPlanForgetsBaselinesOfFreedSubtasks() {
        TaskChain chain = runningChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));
        chain.markFailed("p0-r1-0", "dead end");
        chain.enterReplanningFromStuck("stuck");

        List<Subtask> replacement = List.of(Subtask.hardCoded("p0-r2-0", "new plan",
                Map.of("asset_key", "minecraft:stone", "minimum", 1)));
        chain.replaceCurrentSubtasks(replacement);

        assertNull(chain.acquireBaseline("p0-r1-0"), "被换掉的二级不该留下基线");
        assertEquals("p0-r2-0", chain.currentSubtask().id());
    }

    @Test
    void restoredBaselinePointingAtUnknownSubtaskIsRejectedLoudly() {
        TaskChain chain = activeChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));
        String tampered = chain.toJson().replace("\"acquireBaselines\":{\"p0-r1-0\"",
                "\"acquireBaselines\":{\"ghost-99\"");
        assertNotNull(tampered);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TaskChain.fromJson(tampered));
        assertTrue(ex.getMessage().contains("acquireBaseline"),
                "指向不存在二级的基线必须大声拒绝（查不到基线会让 acquire 静默卡死）：" + ex.getMessage());
    }

    @Test
    void nullCountsCaptureYieldsEmptyBaselineRatherThanNull() {
        TaskChain chain = activeChain();
        chain.captureAcquireBaseline("p0-r1-0", null);
        assertEquals(Map.of(), chain.acquireBaseline("p0-r1-0"),
                "读不到背包 = 基线为全 0（acquire 仍可判），不是 null");
    }

    @Test
    void snapshotStillOmitsBaselineNoiseButRoundTrips() {
        TaskChain chain = activeChain();
        chain.captureAcquireBaseline("p0-r1-0", Map.of("minecraft:wheat_seeds", 33));
        Map<String, Object> view = chain.snapshot();
        assertNotNull(view.get("primaries"));
        TaskChain restored = TaskChain.fromJson(chain.toJson());
        assertEquals(chain.acquireBaseline("p0-r1-0"), restored.acquireBaseline("p0-r1-0"));
    }

    /** 小工具：造一个可变的 map，避免测试里到处写 new LinkedHashMap<>()。 */
    private static final class LinkedHashMapSource {
        Map<String, Integer> map() {
            Map<String, Integer> m = new java.util.LinkedHashMap<>();
            m.put("minecraft:wheat_seeds", 33);
            return m;
        }
    }
}