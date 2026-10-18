package com.dwinovo.numen.plugins.rdd;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ B6：死亡事件链的环形缓冲与冻结快照。
 *
 * <p><b>要解决的那句用户原话</b>：「<b>整个事情的链路都要冻结</b>」——
 * 而现状是每条 death 事件只带最后一刻，前面「怎么走到死的」没有任何留痕。</p>
 */
class TraceRingTest {

    // ---------- TraceRing 本身（纯 JVM）----------

    @Test
    void ringKeepsOrderOldestFirstInSnapshot() {
        TraceRing r = new TraceRing(8);
        r.push(1, "planning", 100L, Map.of("companionId", "a"));
        r.push(2, "execution", 110L, Map.of("companionId", "a"));
        r.push(3, "loop_detected", 120L, Map.of("companionId", "a"));

        TraceRing.Snapshot s = r.snapshot();
        assertEquals(3, s.size());
        // ★ 旧的在前：读一条链的自然方向是「先发生什么、后发生什么」
        assertEquals(1L, s.entries().get(0).seq());
        assertEquals(3L, s.entries().get(2).seq());
    }

    @Test
    void ringEvictsOldestAndSaysItWasTruncated() {
        TraceRing r = new TraceRing(3);
        for (int i = 1; i <= 5; i++) {
            r.push(i, "execution", 100L + i, Map.of("companionId", "a"));
        }
        TraceRing.Snapshot s = r.snapshot();
        assertEquals(3, s.size(), "窗口容量必须被遵守");
        assertEquals(3L, s.entries().get(0).seq(), "最老的被挤掉了");
        assertTrue(s.everTruncated(), "挤过就要说");

        Map<String, Object> m = s.toMap();
        assertEquals(Boolean.TRUE, m.get("trace_truncated"),
                "★ 挤过就必须标 trace_truncated —— 否则「窗口里有 3 条」会被读成「只发生过 3 条」");
        assertTrue(String.valueOf(m.get("trace_note")).contains("instrumentation.jsonl"),
                "要说清更早的历史去哪儿查：" + m.get("trace_note"));
    }

    @Test
    void snapshotAlwaysCarriesLenAndCapacity() {
        TraceRing r = new TraceRing(4);
        r.push(1, "execution", 1L, Map.of("companionId", "a"));
        Map<String, Object> m = r.snapshot().toMap();
        // ★ 容量必须一起报：不然读的人不知道这个数是不是被截过的
        assertEquals(1, m.get("trace_len"));
        assertEquals(4, m.get("trace_capacity"));
        assertFalse(m.containsKey("trace_truncated"), "没挤过就不该出现这个键（别报 0 冒充「没截断」）");
    }

    @Test
    void snapshotDoesNotConsumeTheWindow() {
        TraceRing r = new TraceRing(4);
        r.push(1, "execution", 1L, Map.of());
        r.snapshot();
        r.snapshot();
        r.snapshot();
        assertEquals(1, r.size(), "取快照不该消耗窗口（可以反复看）");
    }

    @Test
    void entryOnlyCarriesTheChosenFields() {
        // ★ 快照是「我自己挑的精简集」，不是全量 data —— 要全量请查 jsonl
        TraceRing r = new TraceRing(4);
        r.push(1, "execution", 1L, Map.of(
                "companionId", "a", "task", "s0", "reason", "blocked",
                " gigantic_payload", "x".repeat(500)));
        Map<String, Object> row = r.snapshot().entries().get(0).toMap();
        assertTrue(row.containsKey("task"));
        assertTrue(row.containsKey("reason"));
        assertFalse(row.containsKey("gigantic_payload"),
                "不在 TRACE_FIELDS 里的字段不进快照（否则 death 行会被撑爆）");
        assertEquals(1L, row.get("seq"));
        assertEquals("execution", row.get("type"));
    }

    @Test
    void emptyRingIsNotNullAndSaysNothing() {
        TraceRing.Snapshot s = new TraceRing(4).snapshot();
        assertEquals(0, s.size());
        assertEquals(List.of(), s.entries());
        Map<String, Object> m = s.toMap();
        assertEquals(0, m.get("trace_len"), "空也要报 0，不是「没测到」");
        assertNotNull(m.get("recent_trace"));
    }

    @Test
    void clearResetsTruncationFlag() {
        TraceRing r = new TraceRing(2);
        r.push(1, "x", 1L, Map.of());
        r.push(2, "x", 2L, Map.of());
        r.push(3, "x", 3L, Map.of());
        assertTrue(r.everTruncated());
        r.clear();
        assertFalse(r.everTruncated(), "clear 之后不该还记着「挤过」");
        assertEquals(0, r.size());
    }

    // ---------- 接线（走真实的 publishAt，落盘后读回）----------

    @TempDir
    Path tmp;

    private void useTmpDir() {
        // ⚠️ 必须走 resetForTest()，不能只 clearTraceRings(null)：
        //   LAST_EMIT_MS（限流状态）是 static 且跨测试残留 —— 上一个测试发过
        //   LOOP_DETECTED 之后，这一个测试再发同型就会被 30s 冷却静默抑制
        //   （throttleAllows 直接 return false，事件根本不落盘）。
        //   踩过这个坑：症状是「期望 4 行只有 1 行」「越界 Index 2 out of bounds」。
        RddInstrumentation.resetForTest();
        RddInstrumentation.setDirOverrideForTest(tmp.resolve("monitor"));
    }

    private List<JsonObject> readEvents() throws Exception {
        Path file = tmp.resolve("monitor").resolve("instrumentation.jsonl");
        if (!Files.exists(file)) return List.of();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(s -> !s.isBlank())
                .map(s -> (JsonObject) com.google.gson.JsonParser.parseString(s).getAsJsonObject())
                .toList();
    }

    /**
     * 取事件里承载「事件专属字段」的那一层。
     *
     * <p>⚠️ 踩过这个坑：一开始直接在信封顶层找 {@code trace_len}，拿到 null，
     * 报了「生产代码没把快照写进去」的假结论。真实形状由 {@code InstrumentationEvents.line()}
     * 定死：{@code schema_version/event_id/timestamp/source/category/type/game_time/data}，
     * 专属字段全在 {@code data} 里（与 {@code MonitoringJournal}/{@code rdd.jsonl} 对齐）。
     *
     * <p>⚠️ 更阴的是反向的坑：{@code assertFalse(e.has("recent_trace"))} 在顶层查
     * <b>永远为 true ⇒ 断言空跑必过</b>。那种「假绿」比红更贵 —— 它让人以为
     * 「非死亡事件不带快照」这条规矩被测住了，其实一行都没测到。</p>
     */
    private static JsonObject data(JsonObject envelope) {
        return envelope.getAsJsonObject("data");
    }

    @Test
    void deathEventFreezesThePrecedingChain() throws Exception {
        useTmpDir();
        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED,
                Map.of("companionId", "a", "task", "s0", "reason", "卡住"), 100L);
        RddInstrumentation.publishAt(RddInstrumentation.REPEAT_GATHER,
                Map.of("companionId", "a", "task", "s0"), 110L);
        RddInstrumentation.publishAt(RddInstrumentation.RESOURCE_WASTE,
                Map.of("companionId", "a", "task", "s0"), 120L);
        RddInstrumentation.publishAt(RddInstrumentation.DEATH,
                Map.of("companionId", "a", "hp", 0), 130L);

        List<JsonObject> events = readEvents();
        assertEquals(4, events.size());
        JsonObject death = data(events.get(3));

        // ★ 这就是「整个链路被冻结」：death 那一行里带着死之前发生过什么
        assertTrue(death.has("recent_trace"), "death 行必须带 recent_trace");
        assertEquals(3, death.get("trace_len").getAsInt());
        assertEquals(TraceRing.DEFAULT_CAPACITY, death.get("trace_capacity").getAsInt());
        var rows = death.getAsJsonArray("recent_trace");
        assertEquals(3, rows.size());
        // ★ 顺序必须是「先发生什么」在前 —— 倒过来的链会让人照着错的因果去复盘
        assertEquals("loop_detected", rows.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("卡住", rows.get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("resource_waste", rows.get(2).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void snapshotExcludesTheDeathItself() throws Exception {
        useTmpDir();
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of("companionId", "a"), 10L);
        JsonObject death = data(readEvents().get(0));
        // 快照在推进窗口之前取 ⇒ 只有「死之前」的事；这里死之前什么都没有
        assertEquals(0, death.get("trace_len").getAsInt(),
                "★ 快照必须只含死之前的事 —— 含了死亡本身就不是「怎么走到死的」了");
    }

    @Test
    void nonDeathEventsDoNotCarryTheSnapshot() throws Exception {
        useTmpDir();
        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, Map.of("companionId", "a"), 1L);
        RddInstrumentation.publishAt(RddInstrumentation.REPEAT_GATHER, Map.of("companionId", "a"), 2L);
        for (JsonObject e : readEvents()) {
            assertFalse(data(e).has("recent_trace"),
                    "★ 只有死亡类事件冻结快照 —— 否则每条事件都胖一圈，jsonl 撑爆");
        }
    }

    @Test
    void chainsAreKeptSeparatePerCompanion() throws Exception {
        useTmpDir();
        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, Map.of("companionId", "a", "task", "ta"), 1L);
        // ⚠️ b 用**另一种**类型：LOOP_DETECTED 有 30s 冷却，同型连发第二条会被静默抑制
        //   （throttleAllows → return false，事件不落盘）⇒ 那测的就不是「分组」而是「限流」。
        //   不给测试加「关限流」开关 —— 那是给生产留一个测试专用旁路。
        RddInstrumentation.publishAt(RddInstrumentation.REPEAT_GATHER, Map.of("companionId", "b", "task", "tb"), 2L);
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of("companionId", "a"), 3L);

        JsonObject deathA = data(readEvents().get(2));
        assertEquals(1, deathA.get("trace_len").getAsInt(), "a 的链里不该混进 b 的事件");
        var row = deathA.getAsJsonArray("recent_trace").get(0).getAsJsonObject();
        assertEquals("ta", row.get("task").getAsString());
    }

    @Test
    void eventsWithoutCompanionStillWriteButGetNoSnapshot() throws Exception {
        useTmpDir();
        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, Map.of("task", "s0"), 1L);
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of(), 2L);
        List<JsonObject> events = readEvents();
        assertEquals(2, events.size(), "没有 companionId 也要照常写（埋点不因缺字段丢事件）");
        assertFalse(data(events.get(1)).has("recent_trace"),
                "★ 取不到 companionId 就不冻 —— 不许猜（猜出来的分组会让「链属于谁」变成假事实）");
    }

    @Test
    void ringReflectsOnlyWhatWasActuallyWritten() throws Exception {
        // 写盘失败时**不推进**缓冲：缓冲回答的是「已记录的链」
        // ⚠️ 必须先 resetForTest()：不重置的话 30s 的 LOOP_DETECTED 冷却会把下面那次 publish
        //   静默抑制掉（throttleAllows 直接 return false）—— 于是 droppedEvents()==0、
        //   事件根本没落盘、ring 也没推进，这个测试**什么都测不到却看着像绿的**。
        //   同一个坑在这个类里踩了两次（另一次是 useTmpDir 的注释记着的）。
        RddInstrumentation.resetForTest();
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        RddInstrumentation.setDirOverrideForTest(blocker);

        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, Map.of("companionId", "a"), 1L);
        assertEquals(1L, RddInstrumentation.droppedEvents(), "写失败照旧计数（fail-silent 不变）");
        assertEquals(0, RddInstrumentation.traceRingSize("a"),
                "★ 写盘失败的事件不许进窗口 —— 否则快照里会有 jsonl 里查不到的事件");

        RddInstrumentation.setDirOverrideForTest(tmp.resolve("monitor"));
        RddInstrumentation.publishAt(RddInstrumentation.DEATH, Map.of("companionId", "a"), 2L);
        // ⚠️ 这里断言的是**内容**不是条数：一次成功写盘后窗口里理应多出这条死亡本身
        //   （publishAt 写成功才 push）。早先写的是 assertEquals(0, ...size())，于是
        //   「窗口该是空的」这个期望本身是错的 —— 它把刚写成功的事件当成了不该有的东西。
        var snap = RddInstrumentation.traceSnapshot("a");
        assertEquals(1, snap.size(), "写成功的那条应该进窗口");
        assertEquals(RddInstrumentation.DEATH, snap.entries().get(0).type(),
                "★ 写盘失败的那条绝不许留在窗口里 —— 否则快照里会有 jsonl 里查不到的事件");
        // 同一件事从落盘侧再验一遍：jsonl 里就只有这一次成功的死亡
        List<JsonObject> events = readEvents();
        assertEquals(1, events.size(), "失败那次不该留下任何行");
        assertEquals(RddInstrumentation.DEATH, events.get(0).get("type").getAsString());
    }

    @Test
    void traceRingsAreKeyedPerCompanionAndReadable() {
        useTmpDir();
        RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, Map.of("companionId", "a"), 1L);
        // 同上：b 用别的类型，别撞 30s 冷却
        RddInstrumentation.publishAt(RddInstrumentation.REPEAT_GATHER, Map.of("companionId", "b"), 2L);
        assertEquals(1, RddInstrumentation.traceRingSize("a"));
        assertEquals(1, RddInstrumentation.traceRingSize("b"));
        assertEquals(0, RddInstrumentation.traceRingSize("nobody"));
        assertEquals(0, RddInstrumentation.traceSnapshot("nobody").size(), "陌生同伴给空快照，不是 null");

        RddInstrumentation.clearTraceRings("a");
        assertEquals(0, RddInstrumentation.traceRingSize("a"));
        assertEquals(1, RddInstrumentation.traceRingSize("b"), "只清一个同伴不该影响别的");
    }
}