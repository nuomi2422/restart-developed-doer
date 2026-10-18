package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B22「只读反馈通道」的契约测试。
 *
 * <p><b>只读这一条不靠本类自证</b> —— 靠编译期：{@link FeedbackChannel} 全类只以
 * {@code Read} 打开文件、没有任何写方法（验收 V8 是 grep 那个文件）。
 * 本类测的是**契约正确性**：游标、坏行、代际、缺失表达。
 */
class FeedbackChannelB22Test {

    private static String line(String type, String dataJson) {
        return "{\"schema_version\":1,\"event_id\":\"e-" + type + "\",\"timestamp\":\"2026-10-01T00:00:00Z\","
                + "\"source\":\"numen\",\"type\":\"" + type + "\",\"data\":" + dataJson + "}";
    }

    private static void write(Path f, String... lines) throws IOException {
        Files.createDirectories(f.getParent());
        Files.write(f, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ---------- 游标 ----------

    @Test
    void firstPullWithCursorZeroReturnsEverything(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s1\",\"companion\":\"rdd\"}"),
                line("death", "{\"companion\":\"rdd\",\"reason\":\"lava\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertTrue(pull.readable);
        assertEquals(2, pull.events.size());
        assertEquals(0, pull.skipped);
        assertTrue(pull.nextCursor > 0);
    }

    @Test
    void secondPullWithCursorReturnsOnlyNewLines(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s1\"}"));
        var p1 = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(1, p1.events.size());
        long c = p1.nextCursor;

        write(f, line("subtask_completed", "{\"subtask\":\"s1\"}"),
                line("subtask_failed", "{\"subtask\":\"s2\",\"reason\":\"stalled\"}"));
        var p2 = FeedbackChannel.pull(f, "live@1", c, null, new FeedbackChannel.Pull());
        assertEquals(1, p2.events.size(), "只该返回新增的那一条");
        // ★ B9 修：这条断言原来写成 `containsKey("subtask") ? "subtask_failed" : "subtask_failed"`
        //   —— 两个分支同值，恒真，所以「返回的是新增那条」从没被验证过。
        //   而 FeedbackChannel.pull 又确实一直没 seek（见那边注释），两个 bug 互相掩护。
        assertEquals("subtask_failed", p2.events.get(0).sourceType(),
                "必须真的是新增那条，不是文件头那条");
        assertTrue(p2.events.get(0).observation().containsKey("reason"),
                "新增那条带 reason；文件头那条没有");
        assertTrue(p2.nextCursor > c, "游标必须前进");
    }

    /**
     * ★ B9 回归测试：非零游标必须真的 seek。
     *
     * <p>旧代码把游标只用来算「读多少字节」、字节仍从文件头读，于是这里会返回
     * {@code [subtask_completed, death]}（文件头两条 + 一条读残的）而不是
     * {@code [death, subtask_failed]}。断言钉的是<b>事件身份</b>不是条数，
     * 因为条数在旧代码下也会碰巧对上。</p>
     */
    @Test
    void nonZeroCursorReallySeeksInsteadOfRereadingTheHead(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        String l1 = line("subtask_completed", "{\"subtask\":\"s1\"}");
        String l2 = line("death", "{\"reason\":\"lava\"}");
        String l3 = line("subtask_failed", "{\"subtask\":\"s3\",\"reason\":\"stalled\"}");
        write(f, l1, l2, l3);

        long afterFirst = l1.getBytes(StandardCharsets.UTF_8).length + 1L;
        var pull = FeedbackChannel.pull(f, "live@1", afterFirst, null, new FeedbackChannel.Pull());

        assertEquals(2, pull.events.size(), "游标之后应恰好剩两条");
        assertEquals("death", pull.events.get(0).sourceType(),
                "第一条必须是 death —— 旧代码这里返回的是文件头的 subtask_completed");
        assertEquals("subtask_failed", pull.events.get(1).sourceType());
        assertEquals(0, pull.skipped, "游标落在行首就不该有读残的行");
    }

    @Test
    void cursorBeyondFileSizeResetsInsteadOfThrowing(@TempDir Path tmp) throws IOException {
        // 文件被截断/重建（换档）时旧偏移会越界 —— 必须自愈，不是崩
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s1\"}"));
        var pull = FeedbackChannel.pull(f, "live@2", 999_999L, null, new FeedbackChannel.Pull());
        assertTrue(pull.readable);
        assertEquals(1, pull.events.size(), "越界游标应从头读");
    }

    @Test
    void unreadableSourceIsHonestFailureNotEmptySuccess(@TempDir Path tmp) {
        var pull = FeedbackChannel.pull(tmp.resolve("nope.jsonl"), "live@1", 0L, null, new FeedbackChannel.Pull());
        assertFalse(pull.readable, "文件不在 = readable=false，必须与「读到 0 条」分开");
        assertEquals(0, pull.events.size());
    }

    // ---------- 坏行 ----------

    @Test
    void brokenLinesAreSkippedAndCountedNotSilentlyDropped(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, "{ this is not json", "{}", "[1,2,3]",
                line("subtask_completed", "{\"subtask\":\"ok\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(1, pull.events.size(), "只有那条好的进来了");
        assertEquals(3, pull.skipped, "3 条坏行必须**如实计数**，不许静默丢");
    }

    @Test
    void blankLinesDoNotCountAsSkipped(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        Files.write(f, "\n\n".concat(line("subtask_completed", "{\"a\":1}")).concat("\n\n")
                .getBytes(StandardCharsets.UTF_8));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(1, pull.events.size());
        assertEquals(0, pull.skipped, "空行不算坏行");
    }

    // ---------- B21：缺失不许伪装成数据 ----------

    @Test
    void eventWithNoSubjectsGetsEmptySubjectRefNotPlaceholders(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("something", "{\"totally\":\"different\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        FeedbackEvent ev = pull.events.get(0);
        assertTrue(ev.subjectRef().isEmpty(), "源事件没有批次身份 → subjectRef 应为空，不许补默认值");
        assertTrue(ev.observation().containsKey("totally"), "观测项原样保留");
    }

    @Test
    void subjectRefCarriesIdentityAndCountWhenSourceHasThem(@TempDir Path tmp) throws IOException {
        // RL-15：判据必须带批次身份 + 数量
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"subtask\":\"s7\",\"companion\":\"rdd\",\"count\":\"18\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        Map<String, Object> s = pull.events.get(0).subjectRef();
        assertEquals("s7", s.get("subtask"));
        assertEquals("rdd", s.get("companion"));
        assertEquals("18", s.get("count"));
    }

    @Test
    void observationSummaryIsAbsentNotEmptyString(@TempDir Path tmp) throws IOException {
        // B21：第 1 批刻意留空 —— 「留空」是诚实，「空串」是伪装
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_failed", "{\"subtask\":\"s1\",\"reason\":\"stalled\"}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        FeedbackEvent ev = pull.events.get(0);
        assertNull(ev.observationSummary());
        assertFalse(ev.toMap().containsKey("observation_summary"),
                "toMap 绝不能放一个空的 observation_summary —— 那会被下游当成「总结为空」");
    }

    @Test
    void eventIdFallsBackToTypeAndTimestampWhenSourceHasNone(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, "{\"type\":\"subtask_completed\",\"timestamp\":\"2026-10-01T01:02:03Z\",\"data\":{\"subtask\":\"s1\"}}");
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals("subtask_completed@2026-10-01T01:02:03Z", pull.events.get(0).eventId());
    }

    @Test
    void eventWithoutTimestampGetsNoInventedOne(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, "{\"type\":\"subtask_completed\",\"data\":{\"subtask\":\"s1\"}}");
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals("subtask_completed@?", pull.events.get(0).eventId(),
                "没有时间戳就用 ?，不许编一个");
        assertEquals("", pull.events.get(0).ts(), "没有时间戳就是空串（不是「现在」）");
    }

    // ---------- kind 归类 ----------

    @Test
    void kindsAreGroupedButUnknownOnesStayUnknown(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"a\":1}"),
                line("death", "{\"a\":1}"),
                line("companion_assets_invalidated", "{\"a\":1}"),
                line("planning_started", "{\"a\":1}"),
                line("some_brand_new_event", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        List<String> kinds = pull.events.stream().map(FeedbackEvent::kind).toList();
        assertEquals("task", kinds.get(0));
        assertEquals("death", kinds.get(1));
        assertEquals("asset", kinds.get(2));
        assertEquals("planning", kinds.get(3));
        assertEquals("other", kinds.get(4), "认不出来就说 other，**不编一个看起来对的家族**");
    }

    @Test
    void kindFilterActuallyFilters(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"a\":1}"), line("death", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, List.of("death"), new FeedbackChannel.Pull());
        assertEquals(1, pull.events.size());
        assertEquals("death", pull.events.get(0).kind());
    }

    /**
     * 2026-10-03（E1 批次）：kind 家族会把「饿死」与「被打死」压成同一个 death，
     * 而入队判定必须分得开 —— {@code RddPlugin.java:684-696} 特意拆成两个 type
     * 就是因为归因方向相反。所以源事件的原始 {@code type} 必须一路带下来。
     */
    @Test
    void theRawSourceTypeSurvivesTheKindCollapse(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("instrumentation.jsonl");
        write(f, line("death", "{\"a\":1}"), line("starvation_death", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(2, pull.events.size());
        List<String> kinds = pull.events.stream().map(FeedbackEvent::kind).toList();
        assertEquals(List.of("death", "death"), kinds, "kind collapses them ...");
        List<String> sourceTypes = pull.events.stream().map(FeedbackEvent::sourceType).toList();
        assertEquals(List.of("death", "starvation_death"), sourceTypes, "... but sourceType must not");
        assertEquals("death", pull.events.get(0).toMap().get("source_type"),
                "and it must reach the rendered map, not just the record component");
        assertEquals("starvation_death", pull.events.get(1).toMap().get("source_type"));
    }

    // ---------- 代际 ----------

    @Test
    void generationChangesWithSaveNameAndSessionLockTime(@TempDir Path tmp) throws IOException {
        Path saveA = Files.createDirectories(tmp.resolve("saves/live"));
        Path lock = saveA.resolve("session.lock");
        Files.write(lock, new byte[]{1});
        String g1 = FeedbackChannel.generationOf(saveA, lock);
        assertTrue(g1.startsWith("live@"), g1);

        Files.setLastModifiedTime(lock, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(lock).toMillis() + 60_000));
        String g2 = FeedbackChannel.generationOf(saveA, lock);
        assertFalse(g1.equals(g2), "session.lock 时间变了 → 代际必须变（换档自愈靠这个）");
    }

    @Test
    void generationIsHonestUnknownWhenNothingAvailable(@TempDir Path tmp) {
        String g = FeedbackChannel.generationOf(null, null);
        assertEquals("unknown@-1", g, "拿不到就说 unknown，不许编一个存档名");
    }

    @Test
    void generationIsStampedOntoEveryEvent(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("subtask_completed", "{\"a\":1}"));
        var pull = FeedbackChannel.pull(f, "live@12345", 0L, null, new FeedbackChannel.Pull());
        assertEquals("live@12345", pull.events.get(0).generation(),
                "代际由通道盖上，下游不用自己判 —— 也就不可能忘记判");
    }

    // ---------- 上限 ----------

    @Test
    void pullIsCappedAndSaysSo(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        StringBuilder sb = new StringBuilder();
        // ★ 200 是字面量（契约：单次拉取上限 200 行），刻意不用常量。
        //   变异测试实测：写成常量时改掉上限本测试不会红——期望值跟着被测值一起变。
        for (int i = 0; i < 200 + 30; i++) {
            sb.append(line("subtask_completed", "{\"i\":\"" + i + "\"}")).append('\n');
        }
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertEquals(200, pull.events.size(), "必须封顶，否则一次调用能吐爆返回值");
        assertTrue(pull.truncated, "被截断要**如实说**，不能装作给全了");
    }

    /**
     * ★ 契约钉死点：封顶与预算的「应该是多少」写成独立断言。
     *
     * <p>意义同 {@code MemoQueueTest.capacityConstantIsPinnedTo64}：
     * 改常量会立刻红，逼人 consciously 确认，而不是让行为测试的期望值跟着漂。
     */
    @Test
    void capsArePinnedToContractValues() {
        assertEquals(200, FeedbackChannel.MAX_LINES_PER_PULL, "单次拉取上限契约是 200 行");
        assertEquals(6000, FeedbackChannel.MAX_OBSERVATION_CHARS, "observation 体积预算契约是 6000 字符");
    }

    @Test
    void emptyButReadableFileIsReadableWithZeroEvents(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        Files.write(f, new byte[0]);
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertTrue(pull.readable, "空文件是「读到了、里面没有」，不是「读不了」");
        assertEquals(0, pull.events.size());
    }

    // ---------- B16：AC 只留接口但必须显式失败 ----------

    @Test
    void unsupportedArtifactSinkThrowsInsteadOfSilentlyNoOp() {
        // 「接了但什么都不做」会让「学习者写不出产物」看起来像成功 → 变成查不到根因的哑故障
        ArtifactSink sink = new UnsupportedArtifactSink();
        assertFalse(sink.available());
        var e1 = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishExperience("e1", Map.of("a", 1)));
        var e2 = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishAcScript("s", "body"));
        var e3 = assertThrows(UnsupportedOperationException.class,
                () -> sink.publishCarrier("c", "content"));
        for (var e : List.of(e1, e2, e3)) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().contains("本批"), "抛错消息必须说明「本批不做」，否则调用方不知道是暂时还是永远");
        }
    }

    @Test
    void hasSubstanceDistinguishesRealEventFromEmptyOne(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("rdd.jsonl");
        write(f, line("x", "{}"));
        var pull = FeedbackChannel.pull(f, "live@1", 0L, null, new FeedbackChannel.Pull());
        assertFalse(pull.events.get(0).hasSubstance(), "空观测 + 空 subjectRef = 没有实质内容的壳事件");
    }

    // ---------- 压平 + 体积闸（2026-10-01 实机踩到 83496 字符撞 MCP 包上限后加）----------

    @Test
    void nestedDataIsFlattenedToDotPathsNotDumpedAsTree() {
        // 实机教训：rdd.jsonl 的 data 里嵌着整条 taskChain，原样 dump 会让一条事件占掉整个 MCP 包
        var j = com.google.gson.JsonParser.parseString(
                "{\"taskChain\":{\"currentSubtaskId\":\"primary-7-r4-0\",\"primaries\":[{\"id\":\"p0\"}]}}")
                .getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        assertTrue(flat.containsKey("taskChain.currentSubtaskId"), "一层嵌套要能点到：" + flat);
        assertFalse(flat.containsKey("taskChain"), "不该保留整棵子树本身");
    }

    @Test
    void arraysBecomeCountPlusFirstFewScalars() {
        var j = com.google.gson.JsonParser.parseString("{\"steps\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}")
                .getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        assertEquals("7", flat.get("steps.count"));
        assertEquals("a", flat.get("steps[0]"));
        assertFalse(flat.containsKey("steps[5]"), "超过 5 个就不该再展开");
        assertEquals("true", String.valueOf(flat.get(FeedbackChannel.OBS_TRUNCATED)),
                "丢掉了后面的元素 → 必须标截断");
    }

    @Test
    void objectArraysAreCountedButTheirContentDroppedAndSaysSo() {
        // rdd.jsonl 里 assets 是**对象数组**（每个箱子/熔炉一条）。整棵展开会爆体积，
        // 所以只给 count。**但丢掉内容必须标截断** —— 默默丢 = 让下游以为那就是全部（B21）。
        var j = com.google.gson.JsonParser.parseString(
                "{\"assets\":[{\"assetId\":\"a\"},{\"assetId\":\"b\"}]}").getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        assertEquals("2", flat.get("assets.count"));
        assertNull(flat.get("assets[0]"), "对象元素不展开");
        assertEquals("true", String.valueOf(flat.get(FeedbackChannel.OBS_TRUNCATED)),
                "对象数组内容被丢掉 → 必须标截断");
    }

    @Test
    void longValuesAreCutAndSaysSo() {
        String big = "x".repeat(1000);
        var j = com.google.gson.JsonParser.parseString("{\"summary\":\"" + big + "\"}").getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        String v = String.valueOf(flat.get("summary"));
        assertTrue(v.length() < big.length(), "超长值必须被裁");
        assertTrue(v.contains("[cut"), "裁了必须说出来：" + v);
        assertEquals("true", String.valueOf(flat.get(FeedbackChannel.OBS_TRUNCATED)));
    }

    @Test
    void depthLimitStopsExpansionAndSaysSo() {
        var j = com.google.gson.JsonParser.parseString(
                "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":\"deep\"}}}}}").getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        assertTrue(flat.containsKey("a.b.c.<depth>"), "深度超限只留「有 N 个子键」：" + flat);
        assertEquals("true", String.valueOf(flat.get(FeedbackChannel.OBS_TRUNCATED)));
    }

    @Test
    void smallDataIsNotMarkedTruncated() {
        // 反向护栏：没截断时**不许**出现截断标记，否则下游会白丢数据
        var j = com.google.gson.JsonParser.parseString("{\"subtask\":\"s1\",\"reason\":\"stalled\"}").getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        assertFalse(flat.containsKey(FeedbackChannel.OBS_TRUNCATED), "小数据不该被标截断：" + flat);
        assertEquals(2, flat.size());
    }

    @Test
    void budgetCapStopsEmittingKeysAndSaysSo() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 400; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"k").append(i).append("\":\"").append("y".repeat(200)).append("\"");
        }
        sb.append('}');
        var j = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
        Map<String, Object> flat = FeedbackChannel.flatten(j, 0);
        assertEquals("true", String.valueOf(flat.get(FeedbackChannel.OBS_TRUNCATED)), "撞预算必须标截断");
        int total = flat.entrySet().stream().mapToInt(e -> e.getKey().length() + String.valueOf(e.getValue()).length()).sum();
        assertTrue(total <= 6000 * 2,
                "压平后体积应受控，实际 " + total);
    }
}