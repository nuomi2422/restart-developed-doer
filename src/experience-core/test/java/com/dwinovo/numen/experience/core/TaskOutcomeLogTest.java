package com.dwinovo.numen.experience.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskOutcomeLog} 的钉子测试 —— E7 回流的「结果源」那一半。
 *
 * <p><b>这里的每一条都在钉「不许犯哪一种错」</b>，尤其是：
 * 游标越界不许退回读文件头（B9 那个 bug 的正面反例）、
 * 没有 status 的老行不许猜成 failed、
 * 主人叫停（stopped）不许算成失败。</p>
 */
class TaskOutcomeLogTest {

    private static final Gson GSON = new Gson();
    private static final UUID ME = UUID.fromString("8d8d379b-b9eb-4803-8f8e-307e22581f1f");
    private static final UUID OTHER = UUID.fromString("c29c5c40-0000-4000-8000-000000000000");

    @TempDir
    Path dir;

    // ---------- 正常路径 ----------

    @Test
    void aFinishedTaskYieldsItsStatusAndTaskName() throws IOException {
        Path f = write(lines(
                taskFinished(ME, 1_000L, "done", "t-7", "goto"),
                bodyLog(ME, 1_010L)));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(1, p.outcomes().size(), "只有 task_finished 算结果");
        TaskOutcomeLog.Outcome o = p.outcomes().get(0);
        assertEquals("done", o.status());
        assertEquals("t-7", o.taskId());
        assertEquals("goto", o.taskName());
        assertEquals(TaskOutcomeLog.Verdict.SUCCEEDED, o.verdict());
        assertEquals(1_000L, o.finishedAtMillis(), "时间戳要解析成毫秒，不能当 0");
        assertNotNull(o.eventId());
        // 证明它真的读了正文里的字段，而不是「看起来对」。
        assertEquals("done", String.valueOf(o.toMap().get("status")));
    }

    @Test
    void onlyTheBuiltInBrainsFiveStatusesMapToAVerdict() {
        assertEquals(TaskOutcomeLog.Verdict.SUCCEEDED, TaskOutcomeLog.Outcome.verdictOf("done"));
        assertEquals(TaskOutcomeLog.Verdict.FAILED, TaskOutcomeLog.Outcome.verdictOf("failed"));
        assertEquals(TaskOutcomeLog.Verdict.FAILED, TaskOutcomeLog.Outcome.verdictOf("timeout"));
        assertEquals(TaskOutcomeLog.Verdict.FAILED, TaskOutcomeLog.Outcome.verdictOf("interrupted"));
        assertEquals(TaskOutcomeLog.Verdict.CANCELLED, TaskOutcomeLog.Outcome.verdictOf("stopped"));
    }

    // ★ B9 那个 bug 的正面反例：游标越界不许退回读文件头。
    @Test
    void aCursorBeyondTheFileIsReportedInsteadOfRereadingTheHead() throws IOException {
        Path f = write(lines(
                taskFinished(ME, 1_000L, "done", "old-1", "goto"),
                taskFinished(ME, 2_000L, "failed", "old-2", "mine"),
                taskFinished(ME, 3_000L, "done", "new-1", "goto")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, Files.size(f) + 4096, 100);

        assertEquals(0, p.outcomes().size(),
                "游标越过文件末尾时绝不能退回从头读（B9 的 FeedbackChannel 就是这么错的）");
        assertTrue(p.cursorBeyondFile(), "必须如实报出游标越界");
        assertNull(p.outcomes().isEmpty() ? null : p.outcomes().get(0), "不许拿文件头那批充数");
    }

    @Test
    void aNonZeroCursorReallySeeks() throws IOException {
        String a = taskFinished(ME, 1_000L, "failed", "first", "goto");
        String b = taskFinished(ME, 2_000L, "done", "second", "mine");
        String c = taskFinished(ME, 3_000L, "done", "third", "goto");
        Path f = write(lines(a, b, c));

        long afterFirst = (a + "\n").getBytes(StandardCharsets.UTF_8).length;
        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, afterFirst, 100);

        assertEquals(2, p.outcomes().size());
        assertEquals("second", p.outcomes().get(0).taskId(), "必须真的从第二行开始");
        assertEquals("third", p.outcomes().get(1).taskId());
        assertFalse(p.outcomes().stream().anyMatch(o -> o.taskId().equals("first")),
                "文件头那条不该出现");
    }

    @Test
    void anotherCompanionsTaskIsNotYours() throws IOException {
        Path f = write(lines(
                taskFinished(OTHER, 1_000L, "done", "x", "goto"),
                taskFinished(ME, 2_000L, "done", "y", "goto")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(1, p.outcomes().size());
        assertEquals("y", p.outcomes().get(0).taskId());
        assertEquals(1, p.otherCompanionEvents(), "别人的也要计数，不能静默消失");
    }

    // ★ B10 之前的老行：attrs 整块被埋点丢掉。这里钉「不许猜」。
    @Test
    void aPreB10LineWithoutStatusIsCountedNotGuessed() throws IOException {
        Path f = write(lines(legacyTaskFinished(ME, 1_000L, "任务完成")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(0, p.outcomes().size(), "没有 status 就不是一条结果，不许按 failed 收");
        assertEquals(1, p.statusAbsent(), "「本来就没有」与「读到了」必须分得开");
        assertEquals(0, p.unknownStatus());
    }

    @Test
    void anUnknownStatusIsCountedAndNeverSilentlyTreatedAsFailure() throws IOException {
        Path f = write(lines(taskFinished(ME, 1_000L, "weird_new_state", "t", "goto")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(1, p.outcomes().size());
        assertNull(p.outcomes().get(0).verdict(), "不认识就是不认识，不许归到 FAILED");
        assertEquals(1, p.unknownStatus());
        assertFalse(p.outcomes().get(0).toMap().get("counts_as_evidence").equals(Boolean.TRUE));
    }

    @Test
    void aBodyLogLineIsNotMistakenForATaskResult() throws IOException {
        Path f = write(lines(
                bodyLog(ME, 1_000L),
                // body_log 也带 attrs —— 它有 status 字段，但事件类型不是 task_finished。
                withAttrs("body_log", ME, 1_001L, "id=b", "status=failed")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(0, p.outcomes().size());
        assertEquals(0, p.statusAbsent(), "非 task_finished 的行不该进 statusAbsent 计数");
    }

    @Test
    void aLineWithABrokenTimestampIsSkippedRatherThanGuessed() throws IOException {
        String bad = "{\"schema_version\":1,\"event_id\":\"e\",\"timestamp\":\"not-a-time\","
                + "\"source\":\"numen\",\"category\":\"events\",\"type\":\"task_finished\",\"data\":"
                + "{\"companion_id\":\"" + ME + "\",\"id\":\"t\",\"task\":\"goto\",\"status\":\"done\","
                + "\"attrs_written\":3}}";
        Path f = write(lines(bad, taskFinished(ME, 2_000L, "done", "ok", "goto")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(1, p.outcomes().size());
        assertEquals("ok", p.outcomes().get(0).taskId());
        assertEquals(1, p.unparsableTimestamp());
    }

    @Test
    void aHalfWrittenLastLineIsDroppedInsteadOfParsedIntoHalfAJson() throws IOException {
        String good = taskFinished(ME, 2_000L, "done", "ok", "goto");
        String truncated = "{\"schema_version\":1,\"type\":\"task_finished\",\"data\":{\"companio";
        Path f = write(good + "\n" + truncated);

        TaskOutcomeLog.Pull p = TaskOutcomeLog.tail(f, ME, 100);

        assertEquals(1, p.outcomes().size(), "写了一半的行要丢掉");
        assertEquals("ok", p.outcomes().get(0).taskId());
        assertEquals(0, p.skippedLines(), "丢掉半行不是「跳过坏行」，别混进计数");
    }

    @Test
    void tailFindsTheLastLineWhenItIsShorterThanTheTailWindow() throws IOException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(taskFinished(ME, 1_000L + i, i == 39 ? "done" : "failed", "t-" + i, "goto"));
        }
        Path f = write(lines(many.toArray(new String[0])));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.tail(f, ME, 100);

        assertEquals(40, p.outcomes().size());
        assertEquals("t-39", p.outcomes().get(p.outcomes().size() - 1).taskId());
    }

    @Test
    void anEmptyOrMissingFileIsReportedRatherThanThrowing() {
        TaskOutcomeLog.Pull missing = TaskOutcomeLog.pull(dir.resolve("nope.jsonl"), ME, 0, 10);
        assertEquals(0, missing.outcomes().size());
        assertEquals(1, missing.skippedLines(), "读不到要计数，不能装作「没有事件」");

        assertEquals(0, TaskOutcomeLog.tail(null, ME, 10).outcomes().size());
        assertEquals(0, TaskOutcomeLog.tail(dir.resolve("nope.jsonl"), ME, 10).outcomes().size());
    }

    @Test
    void aNullCompanionOrPathIsANoOpRatherThanACrash() {
        assertEquals(0, TaskOutcomeLog.pull(null, ME, 0, 10).outcomes().size());
        assertEquals(0, TaskOutcomeLog.pull(dir.resolve("x"), null, 0, 10).outcomes().size());
        assertNull(TaskOutcomeLog.pathOf(null));
    }

    @Test
    void thePathIsTheOneKnownNameUnderTheConfigDir() {
        Path p = TaskOutcomeLog.pathOf(dir);
        assertNotNull(p);
        assertTrue(p.endsWith(Path.of("monitor", "events.jsonl")),
                "只认 monitor/events.jsonl 这一个已知名，实际=" + p);
    }

    @Test
    void theLineCapCountsMyOwnTasksSoOthersCannotStarveTheWindow() throws IOException {
        // B11 实机抓到的：events.jsonl 里别人同伴的 task_finished 占大头。
        List<String> ls = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ls.add(taskFinished(OTHER, 1_000L + i, "done", "other-" + i, "goto"));
        }
        for (int i = 0; i < 5; i++) {
            ls.add(taskFinished(ME, 9_000L + i, "done", "mine-" + i, "goto"));
        }
        Path f = write(lines(ls.toArray(new String[0])));

        // 上限 3 行：只数我自己的，不该被前面 500 行别人的吃掉。
        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 3);

        assertEquals(3, p.outcomes().size(), "上限只数本同伴的 task_finished");
        assertEquals("mine-0", p.outcomes().get(0).taskId());
        assertEquals(500, p.otherCompanionEvents());
        assertTrue(p.truncated(), "超上限要如实报");
        assertTrue(p.linesScanned() >= 503, "实际扫过的行数要如实报出来（读了 503 行才截断）");
    }

    @Test
    void aBrokenJsonLineIsCountedAndDoesNotStopTheScan() throws IOException {
        Path f = write(lines(
                "{ this is not json",
                taskFinished(ME, 2_000L, "done", "ok", "goto")));

        TaskOutcomeLog.Pull p = TaskOutcomeLog.pull(f, ME, 0, 100);

        assertEquals(1, p.outcomes().size(), "坏行不许把后面整段吃掉");
        assertEquals(1, p.skippedLines());
    }

    // ---------- helpers ----------

    private Path write(String content) throws IOException {
        Path f = dir.resolve("events.jsonl");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private static String lines(String... ls) {
        StringBuilder sb = new StringBuilder();
        for (String l : ls) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static String taskFinished(UUID cid, long atMillis, String status, String id, String task) {
        return withAttrs(TaskOutcomeLog.TYPE_TASK_FINISHED, cid, atMillis,
                "id=" + id, "task=" + task, "status=" + status, "attrs_written=3");
    }

    private static String bodyLog(UUID cid, long atMillis) {
        JsonObject data = new JsonObject();
        data.addProperty("urgent", false);
        data.addProperty("companion_id", cid.toString());
        data.addProperty("message", "她淹死了");
        data.addProperty("attrs_written", 0);
        return env("body_log", atMillis, data);
    }

    /** B10 之前的老行：attrs 整块被埋点丢掉，只有三个固定键。 */
    private static String legacyTaskFinished(UUID cid, long atMillis, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("urgent", false);
        data.addProperty("companion_id", cid.toString());
        data.addProperty("message", message);
        return env(TaskOutcomeLog.TYPE_TASK_FINISHED, atMillis, data);
    }

    private static String withAttrs(String type, UUID cid, long atMillis, String... keyEquals) {
        JsonObject data = new JsonObject();
        data.addProperty("urgent", false);
        data.addProperty("companion_id", cid.toString());
        data.addProperty("message", "任务收尾");
        for (String pair : keyEquals) {
            int eq = pair.indexOf('=');
            data.addProperty(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return env(type, atMillis, data);
    }

    private static String env(String type, long atMillis, JsonObject data) {
        JsonObject env = new JsonObject();
        env.addProperty("schema_version", 1);
        env.addProperty("event_id", "events-" + atMillis + "-0");
        env.addProperty("timestamp", Instant.ofEpochMilli(atMillis).toString());
        env.addProperty("source", "numen");
        env.addProperty("category", "events");
        env.addProperty("type", type);
        env.add("data", data);
        return GSON.toJson(env);
    }
}