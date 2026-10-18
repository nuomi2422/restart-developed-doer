package com.dwinovo.numen.experience.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 「任务收尾结果」的只读通道 —— E7（使用结果回流）缺的那半边<b>结果源</b>。
 *
 * <p><b>为什么需要它</b>：回流的两个端点此前各自存在却对不上 ——
 * 上游 {@link PresentationReceipt} 知道「哪条经验被呈现过、何时呈现」，
 * 下游 {@code recordEvidence} 会执行升级/降级/撤回，
 * 但中间<b>没有一样东西记录「那次任务后来成没成」</b>。
 * 于是 AI 拿到一份待回报清单时无从判断该回报什么，
 * 只能不回报 ⇒ E7 在实机 66 条经验上 {@code verified_at} 只有 1 条人工自测。</p>
 *
 * <p><b>数据源是 {@code config/numen/monitor/events.jsonl}</b>，
 * 由 {@code NumenEvents.taskFinished} 写入。属性 {@code id / task / status}
 * 是 B10 才补进埋点的（B10 之前 {@code MonitoringJournal} 只发
 * {@code urgent / companion_id / message}，把算好的 attrs 整块丢掉，
 * 实机 3349 条 {@code task_finished} 没一条能回答成没成）。
 * <b>所以本类能读到的带 status 的行，只有 B10 之后新游戏会话里产生的那些</b> ——
 * 老行一律计进 {@code status_absent}，不猜、不补。</p>
 *
 * <p><b>⚠️ 结构性上限：只有内置大脑派的任务才有 {@code task_finished}。</b>
 * {@code CompanionBrain.shipResults} 对 {@code isExternalCall()} 的记录直接
 * {@code continue}（外部驱动靠 {@code task_status} 轮询闭环），
 * {@code CompanionEvent.DEATH} 走 {@code dropActiveNoResult} 也不经过它。
 * ⇒ <b>MCP 派的任务在这里永远看不到</b>。这不是本类的缺陷，是事件生产侧的口径，
 * 报出来是为了别让人把「查不到」读成「没发生过」。</p>
 *
 * <p><b>本类只读、纯 JVM、无副作用。</b>没有 {@code write} / {@code append} /
 * {@code delete}（同 {@code FeedbackChannel} 的编译期纪律）。
 * 文件不存在 / 被轮转 / 行损坏都如实计数上报，绝不静默返回空清单。</p>
 */
public final class TaskOutcomeLog {

    /** 相对 {@code config/numen/} 的已知文件名（不参数化：通道只认这一个）。 */
    public static final String RELATIVE_PATH = "monitor/events.jsonl";
    /** 只认这一种事件类型。 */
    public static final String TYPE_TASK_FINISHED = "task_finished";
    /** 单次拉取最多读多少行。 */
    public static final int DEFAULT_MAX_LINES = 200;
    /** 单次最多读多少字节（events.jsonl 能到 16 MB 上限，防御性）。 */
    private static final int MAX_BYTES_PER_PULL = 2 * 1024 * 1024;
    /** {@link #tail} 往回读多少字节（够覆盖最近几百条事件）。 */
    private static final int TAIL_BYTES = 512 * 1024;

    private TaskOutcomeLog() {
    }

    /**
     * 一次任务收尾的结果，以及它到底能不能当「经验对不对」的证据。
     *
     * <p><b>{@link Verdict#CANCELLED} 是刻意存在的第三种</b>：
     * {@code stopped} 的含义是「主人自己叫停的」（见 {@code NumenEvents.taskFinished}
     * 里急不急只问 {@code !"stopped".equals(status)}）。
     * 主人叫停既没成也没被证伪 ⇒ 把它算成失败会给不相干的经验加反例，
     * 连三次还能把它降级。这一格留空比猜一个方向便宜得多。</p>
     */
    public enum Verdict {
        /** {@code done}：这次任务成了。可以当支持。 */
        SUCCEEDED(true),
        /** {@code failed / timeout / interrupted}：没成。当反例。 */
        FAILED(true),
        /** {@code stopped}：主人叫停。{@code counts_as_evidence} 为 false。 */
        CANCELLED(false);

        private final boolean countsAsEvidence;

        Verdict(boolean countsAsEvidence) {
            this.countsAsEvidence = countsAsEvidence;
        }

        public boolean countsAsEvidence() {
            return countsAsEvidence;
        }
    }

    /** 一次任务收尾。 */
    public record Outcome(String taskId, String taskName, String status,
                           Verdict verdict, long finishedAtMillis, String eventId) {

        /**
         * 由原始 {@code status} 字符串定 {@link Verdict}。
         *
         * <p><b>未知取值一律 {@code null}（= 不是证据）</b>，不归到 FAILED。
         * 实机出现过五种：{@code done/failed/timeout/stopped}（{@code shipResults}
         * 的 switch）+ {@code interrupted}（{@code TaskSlot.dropNoResult}，
         * 因死亡而中断）+ 老代码里的第四种（{@code TaskPersistence} 也发 failed）。
         * 以后新增一种时，这里会返回 null 并被计入 {@code unknown_status}，
         * <b>而不是悄悄按失败处理</b>。</p>
         */
        public static Verdict verdictOf(String status) {
            if (status == null) {
                return null;
            }
            return switch (status.trim().toLowerCase(Locale.ROOT)) {
                case "done", "success", "succeeded" -> Verdict.SUCCEEDED;
                case "failed", "failure", "timeout", "interrupted" -> Verdict.FAILED;
                case "stopped", "cancelled", "canceled" -> Verdict.CANCELLED;
                default -> null;
            };
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("task_id", taskId);
            out.put("task", taskName);
            out.put("status", status);
            out.put("outcome", verdict == null ? "" : verdict.name());
            out.put("counts_as_evidence", verdict != null && verdict.countsAsEvidence());
            out.put("finished_at", finishedAtMillis);
            out.put("event_id", eventId);
            return out;
        }
    }

    /** 一次拉取的读数。字段刻意做多：区分「没有」与「读不到」。 */
    public record Pull(List<Outcome> outcomes, long nextCursor, int linesScanned,
                       boolean truncated, boolean cursorBeyondFile, boolean rotatedSuspected,
                       int otherCompanionEvents, int statusAbsent, int unknownStatus,
                       int unparsableTimestamp, int skippedLines) {

        public Map<String, Object> readout() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("outcomes", outcomes.size());
            out.put("lines_scanned", linesScanned);
            out.put("next_cursor", nextCursor);
            out.put("truncated", truncated);
            out.put("cursor_beyond_file", cursorBeyondFile);
            out.put("rotated_suspected", rotatedSuspected);
            out.put("other_companion_events", otherCompanionEvents);
            out.put("status_absent", statusAbsent);
            out.put("unknown_status", unknownStatus);
            out.put("unparsable_timestamp", unparsableTimestamp);
            out.put("skipped_lines", skippedLines);
            return out;
        }
    }

    /**
     * 从 {@code cursorByte} 起拉本同伴的 {@code task_finished}。
     *
     * @param cursorByte 字节游标（{@code events.jsonl} 是 JSONL，一个字节都不能错位）
     */
    public static Pull pull(Path eventsJsonl, UUID companionId, long cursorByte, int maxLines) {
        List<Outcome> out = new ArrayList<>();
        if (eventsJsonl == null || companionId == null) {
            return new Pull(List.of(), Math.max(0L, cursorByte), 0, false, false, false, 0, 0, 0, 0, 0);
        }
        int cap = maxLines <= 0 ? DEFAULT_MAX_LINES : maxLines;
        long start = Math.max(0L, cursorByte);
        long size;
        try {
            size = Files.size(eventsJsonl);
        } catch (IOException ex) {
            return new Pull(List.of(), start, 0, false, false, false, 0, 0, 0, 0, 1);
        }
        // ★ B9 教训的正面写法：游标越过文件末尾时**绝不退回从头读**。
        //   FeedbackChannel 就是这么写的 bug —— 任何非零游标被静默忽略，
        //   每次都返回文件里最老的那批事件，看起来「有数据」实则全是陈年旧账。
        //   这里宁可返回空并如实说「游标越界 / 疑似轮转」。
        if (start >= size) {
            boolean rotated = start > 0 && size > 0 && start - size > 1024 * 1024;
            return new Pull(List.of(), start, 0, false, true, rotated, 0, 0, 0, 0, 0);
        }

        byte[] bytes;
        try (InputStream in = Files.newInputStream(eventsJsonl)) {
            if (!skipFully(in, start)) {
                // 跳不满就别硬读：读到的会是错位的半个 JSON，报出来比吞掉更可信。
                return new Pull(List.of(), start, 0, false, false, false, 0, 0, 0, 0, 0);
            }
            bytes = in.readNBytes(MAX_BYTES_PER_PULL);
        } catch (IOException ex) {
            return new Pull(List.of(), start, 0, false, false, false, 0, 0, 0, 0, 1);
        }

        Acc acc = new Acc();
        boolean truncated = scan(bytes, 0, companionId, cap, out, acc);
        long consumed = start + acc.consumed;
        return new Pull(List.copyOf(out), consumed, acc.scanned, truncated, false, false,
                acc.other, acc.statusAbsent, acc.unknownStatus, acc.badTs, acc.skipped);
    }

    /**
     * 读文件尾部若干行（不做游标）—— 给「只读提示」用。
     *
     * <p><b>为什么不用游标</b>：待回报清单是会话内的，问一句答一句，
     * 要 AI 自己记字节偏移去续读没有意义（而且偏移一旦记错就静默读不到新行）。
     * 这里直接看尾部：只读、无状态、永远给出「最近发生了什么」。</p>
     *
     * <p><b>会丢掉最后一行</b>（如果文件末尾没有换行符）：游戏正在写，
     * 读到的那半行是残的。宁可少一条也不把半个 JSON 报成事实。</p>
     */
    public static Pull tail(Path eventsJsonl, UUID companionId, int maxLines) {
        List<Outcome> out = new ArrayList<>();
        if (eventsJsonl == null || companionId == null) {
            return new Pull(List.of(), 0L, 0, false, false, false, 0, 0, 0, 0, 0);
        }
        int cap = maxLines <= 0 ? DEFAULT_MAX_LINES : maxLines;
        long size;
        try {
            size = Files.size(eventsJsonl);
        } catch (IOException ex) {
            return new Pull(List.of(), 0L, 0, false, false, false, 0, 0, 0, 0, 1);
        }
        if (size == 0) {
            return new Pull(List.of(), 0L, 0, false, false, false, 0, 0, 0, 0, 0);
        }
        long from = Math.max(0L, size - TAIL_BYTES);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(eventsJsonl)) {
            if (!skipFully(in, from)) {
                return new Pull(List.of(), 0L, 0, false, false, false, 0, 0, 0, 0, 0);
            }
            bytes = in.readNBytes((int) Math.min(size - from, TAIL_BYTES));
        } catch (IOException ex) {
            return new Pull(List.of(), 0L, 0, false, false, false, 0, 0, 0, 0, 1);
        }
        int begin = 0;
        if (from > 0) {
            // from 落在一行中间：丢掉开头那半行。
            while (begin < bytes.length && bytes[begin] != '\n') {
                begin++;
            }
            if (begin < bytes.length) {
                begin++;
            }
        }
        int end = bytes.length;
        if (end > begin && bytes[end - 1] != '\n') {
            // 末尾是写了一半的行：丢掉。
            while (end > begin && bytes[end - 1] != '\n') {
                end--;
            }
            if (end == begin) {
                return new Pull(List.of(), 0L, 0, true, false, false, 0, 0, 0, 0, 0);
            }
        }
        byte[] slice = new byte[end - begin];
        System.arraycopy(bytes, begin, slice, 0, slice.length);
        Acc acc = new Acc();
        boolean truncated = scan(slice, 0, companionId, cap, out, acc);
        return new Pull(List.copyOf(out), size, acc.scanned, truncated, false, false,
                acc.other, acc.statusAbsent, acc.unknownStatus, acc.badTs, acc.skipped);
    }

    /** 逐行扫 JSONL；返回 true = 因 {@code cap} 截断。 */
    private static boolean scan(byte[] bytes, int from, UUID companionId, int cap,
                                List<Outcome> out, Acc acc) {
        boolean truncated = false;
        int i = from;
        while (i < bytes.length) {
            int nl = i;
            while (nl < bytes.length && bytes[nl] != '\n') {
                nl++;
            }
            int len = nl - i;
            String line = new String(bytes, i, len, StandardCharsets.UTF_8);
            i = nl + 1;
            acc.consumed += len + 1L;
            if (line.isBlank()) {
                continue;
            }
            acc.scanned++;
            JsonObject env;
            try {
                JsonElement parsed = JsonParser.parseString(line);
                if (!parsed.isJsonObject()) {
                    acc.skipped++;
                    continue;
                }
                env = parsed.getAsJsonObject();
            } catch (RuntimeException ex) {
                acc.skipped++;
                continue;
            }
            if (!TYPE_TASK_FINISHED.equals(str(env, "type"))) {
                continue;
            }
            // ★ 上限只数「本同伴的 task_finished」。
            //   B11 实机抓到的：events.jsonl 里别人同伴的 task_finished 占了大头
            //   （120 行里 97 行是别人的），原先按「所有行」计数会让上限被别人的行吃光，
            //   自己同伴的行被挤出窗口 —— 而读数上只显示 truncated:true，
            //   看起来像「本来就没有结果」。
            if (acc.mineSeen >= cap) {
                truncated = true;
                break;
            }
            JsonObject data = obj(env, "data");
            if (data == null) {
                acc.skipped++;
                continue;
            }
            if (!companionId.toString().equals(str(data, "companion_id"))) {
                acc.other++;
                continue;
            }
            acc.mineSeen++;
            String status = str(data, "status");
            if (status == null || status.isBlank()) {
                // ★ B10 之前的老行就是这样：attrs 整块被埋点丢掉。
                //   不猜「大概是 failed」—— 那正是 59 号 §4.3 说的「把碰巧当成必然」。
                acc.statusAbsent++;
                continue;
            }
            long at;
            try {
                at = Instant.parse(str(env, "timestamp")).toEpochMilli();
            } catch (DateTimeParseException | NullPointerException ex) {
                acc.badTs++;
                continue;
            }
            Verdict verdict = Outcome.verdictOf(status);
            if (verdict == null) {
                acc.unknownStatus++;
            }
            out.add(new Outcome(str(data, "id"), str(data, "task"), status, verdict, at, str(env, "event_id")));
        }
        return truncated;
    }

    /** {@code scan} 的可变计数袋（record 太啰嗦，这里本来就只是一堆累加器）。 */
    private static final class Acc {
        private long consumed;
        private int scanned;
        /** 本同伴的 task_finished 行数（上限只数它，不数别人的）。 */
        private int mineSeen;
        private int other;
        private int statusAbsent;
        private int unknownStatus;
        private int badTs;
        private int skipped;
    }

    /** {@code config/numen/monitor/events.jsonl} 的完整路径。 */
    public static Path pathOf(Path configDir) {
        return configDir == null ? null : configDir.resolve("monitor").resolve("events.jsonl");
    }

    /** 跳到 {@code target}；跳不满返回 false（而不是假装跳到了）。 */
    private static boolean skipFully(InputStream in, long target) throws IOException {
        long left = target;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                // skip 可能只跳一部分甚至 0；退化成 read 一个字节来推进。
                int b = in.read();
                if (b < 0) {
                    return false;
                }
                left--;
            } else {
                left -= skipped;
            }
        }
        return true;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || key == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || key == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}