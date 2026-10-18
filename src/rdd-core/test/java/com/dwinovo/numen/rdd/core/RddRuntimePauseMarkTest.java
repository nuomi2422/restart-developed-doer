package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 暂停计时（R03）。
 *
 * <p>深审发现旧实现 {@code pausedTicks()} 用 {@code remove()}：Detector 每 tick 问一次，
 * 第一次问就把暂停起点删了，之后恒返回 0 —— 「到 60 秒复评」那道闸**永远不会触发**，
 * 这正是实机两次 PAUSE 卡死的直接原因。探针实测 {@code firstRead=50 secondRead=0}。
 */
class RddRuntimePauseMarkTest {

    private static TaskChain chainWith(String id) {
        var step = Subtask.hardCoded(id, "do it", Map.of("asset_key", "minecraft:dirt", "minimum", 1));
        return new TaskChain(new Goal("g", "goal", List.of(new PrimaryGoal("p", "phase", List.of(step)))));
    }

    @Test void readingDoesNotConsumeTheStart() {
        var chain = chainWith("s");
        chain.startCurrent();
        chain.pauseSubtask("s", "soldier PAUSE: no village within 4000 blocks");
        var rt = new RddRuntime(chain, new AssetRegistry());
        rt.markPaused("s", 1000L);

        // 关键回归：连读三次，值必须单调增长，而不是"第一次之后永远是 0"
        assertEquals(0L, rt.pausedTicks("s", 1000L));
        assertEquals(200L, rt.pausedTicks("s", 1200L));
        assertEquals(500L, rt.pausedTicks("s", 1500L));
        assertEquals(500L, rt.pausedTicks("s", 1500L), "同一时刻重复读必须稳定");
    }

    @Test void clearIsTheOnlyWayToDropTheStart() {
        var chain = chainWith("s");
        chain.startCurrent();
        chain.pauseSubtask("s", "hold");
        var rt = new RddRuntime(chain, new AssetRegistry());
        rt.markPaused("s", 100L);
        assertTrue(rt.hasPausedMark("s"));
        rt.clearPaused("s");
        assertFalse(rt.hasPausedMark("s"));
        assertEquals(0L, rt.pausedTicks("s", 999_999L));
    }

    @Test void rePausingDoesNotPushTheDeadlineOut() {
        // putIfAbsent 语义：每 tick 都会 markPaused，如果它改成 put，
        // 复评闸就永远等不到 —— 每次 tick 都把起点推到"现在"。
        var chain = chainWith("s");
        chain.startCurrent();
        chain.pauseSubtask("s", "hold");
        var rt = new RddRuntime(chain, new AssetRegistry());
        rt.markPaused("s", 100L);
        for (long t = 110; t <= 300; t += 10) {
            rt.markPaused("s", t);
        }
        assertEquals(200L, rt.pausedTicks("s", 300L), "反复标记不能刷新起点");
    }

    @Test void marksSurviveTheRuntimeThatOwnsThem() {
        // 重启后 PAUSED 状态从 TaskChain 恢复，但计时原本只在内存 → 复评闸又永远不触发。
        // 有了 pausedMarks/restorePausedMark，宿主才能把计时一起持久化。
        var chain = chainWith("s");
        chain.startCurrent();
        chain.pauseSubtask("s", "hold");
        var rt = new RddRuntime(chain, new AssetRegistry());
        rt.markPaused("s", 4242L);
        Map<String, Long> saved = rt.pausedMarks();
        assertEquals(4242L, saved.get("s").longValue());

        var restored = new RddRuntime(TaskChain.fromJson(chain.toJson()), new AssetRegistry());
        assertEquals(SubtaskStatus.PAUSED, restored.chain().currentSubtaskStatus());
        assertFalse(restored.hasPausedMark("s"), "恢复出的链还没有计时（宿主要负责还原）");
        saved.forEach(restored::restorePausedMark);
        assertEquals(3L, restored.pausedTicks("s", 4245L));
    }

    @Test void unknownSubtaskReportsZeroRatherThanThrowing() {
        var rt = new RddRuntime(chainWith("s"), new AssetRegistry());
        assertEquals(0L, rt.pausedTicks("never-paused", 5000L));
        assertEquals(0L, rt.pausedTicks(null, 5000L));
        assertFalse(rt.hasPausedMark(null));
    }
}
