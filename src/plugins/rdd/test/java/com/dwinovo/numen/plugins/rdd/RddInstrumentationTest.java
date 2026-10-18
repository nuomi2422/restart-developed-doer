package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.InstrumentationEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RddInstrumentation 插件侧验收（埋点工单）：
 * <ul>
 *   <li>单一 JSONL append-only、每行可 parse（零坏行）；</li>
 *   <li>fail-silent + dropped_events 自报告；</li>
 *   <li>噪音型事件限流（磁盘算账）；</li>
 *   <li>instrumentation_change 记录修埋点本身；</li>
 *   <li>死亡→恢复失败的时间窗判定。</li>
 * </ul>
 */
class RddInstrumentationTest {

    @TempDir Path tmp;

    @BeforeEach
    void fresh() {
        RddInstrumentation.resetForTest();
        RddInstrumentation.setDirOverrideForTest(tmp.resolve("monitor"));
        RddInstrumentation.setGameTimeSourceForTest(() -> -1L);
    }

    @Test void publishesParseableJsonlLines() throws Exception {
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of("companionId", "a", "task", "s0"), 100L);
        RddInstrumentation.publishAt(RddInstrumentation.STARVATION_DEATH, Map.of("companionId", "a", "task", "s0"), 101L);
        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, Map.of("companionId", "a", "task", "s0"), 102L);

        Path file = tmp.resolve("monitor").resolve("instrumentation.jsonl");
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "每行一个事件，append-only");
        for (String line : lines) {
            var parsed = InstrumentationEvents.parse(line);   // 坏行会抛 → 测试失败（零坏行验收）
            assertTrue(parsed.gameTime() >= 100);
        }
        assertEquals(3, RddInstrumentation.writtenEvents());
        assertEquals(0, RddInstrumentation.droppedEvents());
    }

    @Test void noisyTypesAreThrottled() {
        RddInstrumentation.publishAt(RddInstrumentation.ASSET_MISMATCH, Map.of(), 1L);
        RddInstrumentation.publishAt(RddInstrumentation.ASSET_MISMATCH, Map.of(), 2L);   // 同型 60s 冷却内
        RddInstrumentation.publishAt(RddInstrumentation.ASSET_MISMATCH, Map.of(), 3L);
        assertEquals(1, RddInstrumentation.writtenEvents(), "同型高频事件被限流");
        assertEquals(2, RddInstrumentation.suppressedEvents());

        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of(), 4L);   // 死亡类不设冷却
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of(), 5L);
        assertEquals(3, RddInstrumentation.writtenEvents());
    }

    @Test void writeFailureBumpsDroppedAndSelfReportsOnNextSuccess() throws Exception {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        RddInstrumentation.setDirOverrideForTest(blocker);      // createDirectories 撞文件 → 写失败

        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of(), 1L);
        assertEquals(1, RddInstrumentation.droppedEvents(), "fail-silent：坏目录计数而不抛");

        RddInstrumentation.setDirOverrideForTest(tmp.resolve("monitor"));
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of(), 2L);
        assertEquals(0, RddInstrumentation.droppedEvents(), "已 getAndSet 清零（包袱挂在成功事件上）");

        Path file = tmp.resolve("monitor").resolve("instrumentation.jsonl");
        String line = Files.readString(file).trim();
        var parsed = InstrumentationEvents.parse(line);
        assertEquals(1, ((Number) parsed.data().get("dropped_events_since_previous")).intValue(),
                "上次丢的自报告在下次成功事件上");
        assertEquals(1, RddInstrumentation.writtenEvents());
    }

    @Test void changeRecordsInstrumentationChange() throws Exception {
        RddInstrumentation.change("switched asset_mismatch cooldown to 60s");
        Path file = tmp.resolve("monitor").resolve("instrumentation.jsonl");
        var parsed = InstrumentationEvents.parse(Files.readString(file).trim());
        assertEquals(InstrumentationEvents.INSTRUMENTATION_CHANGE, parsed.type());
        assertEquals("switched asset_mismatch cooldown to 60s", parsed.data().get("what"));
    }

    @Test void recentDeathWindow() {
        UUID id = UUID.randomUUID();
        RddInstrumentation.recordDeathTick(id, 1000L);
        assertTrue(RddInstrumentation.recentDeath(id, 1000L), "死亡当拍");
        assertTrue(RddInstrumentation.recentDeath(id, 5000L), "5 分钟内");
        assertFalse(RddInstrumentation.recentDeath(id, 7001L), "超过窗=不算恢复失败");
        assertFalse(RddInstrumentation.recentDeath(UUID.randomUUID(), 1000L), "无死亡记录");
        assertFalse(RddInstrumentation.recentDeath(id, -1L), "无游戏时钟");
    }
}