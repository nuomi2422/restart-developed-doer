package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.PriorRound;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读 {@code monitor/learner.jsonl}，把上轮产出交给复盘 prompt 用。
 *
 * <p><b>★ 为什么读自己的日志而不是另建一个「上轮状态」文件</b>：
 * 上一轮的产物已经被 {@link LearnerReviewTool} 整份写进 {@code reviewed} 事件
 * （{@code data.verdicts[]}），另建一个文件等于<b>把同一份事实写两遍</b> ——
 * 两份会漂，而漂了以后没人知道该信哪份。仓库既有先例：
 * {@code FeedbackChannel} 只读 {@code rdd.jsonl}、{@code LearnerIntakeTool} 只读
 * {@code instrumentation.jsonl}，都是「读已有的日志、不新建文件」。
 *
 * <p><b>★ 只读纪律</b>：本类<b>只以 {@code READ} 方式</b>打开文件，
 * 全类<b>没有</b> {@code write} / {@code append} / {@code delete} 调用 ——
 * 与 {@code FeedbackChannel} 同款可 grep 验收（grep 本文件有没有写方法，必须是 0）。
 *
 * <p>读失败<b>如实降级成「没有上轮」</b>并把原因带进读数，不抛：复盘这条路本来就
 * 是「锦上添花」，读不到上轮不该让本轮复盘一起失败。
 */
final class PriorRoundReader {

    /** 读尾部多少字节。够覆盖最近两轮（每轮一条 reviewed 事件），又不把整个文件读进内存。 */
    private static final int TAIL_BYTES = 2 * 1024 * 1024;

    private PriorRoundReader() {
    }

    /**
     * 读上轮回顾。
     *
     * @return 读数（永远非 null，{@code ok=false} 时 {@code error} 说明原因）
     */
    static Read read(int maxRounds, int maxProductChars) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Path file = dir.resolve("learner.jsonl");
            if (!Files.isRegularFile(file)) {
                // 不是异常：第一次跑就是这样，如实说「日志文件还不存在」
                return new Read(PriorRound.Summary.none(), false,
                        "no learner.jsonl yet (" + file + ")", 0);
            }
            long size = Files.size(file);
            int want = (int) Math.min(size, TAIL_BYTES);
            byte[] buf;
            try (var in = Files.newInputStream(file)) {
                long skip = size - want;
                // ★ 必须真的跳过去：开了流却从 0 读，cursor 就永远无效
                //   （B9 在 FeedbackChannel 上踩过这个坑：两条只读通道从上线起就一直在读文件头）
                long skipped = 0;
                while (skipped < skip) {
                    long n = in.skip(skip - skipped);
                    if (n <= 0) {
                        break;
                    }
                    skipped += n;
                }
                buf = in.readNBytes(want);
            }
            String text = new String(buf, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String l : text.split("\r?\n")) {
                if (!l.isBlank()) {
                    lines.add(l);
                }
            }
            PriorRound.Summary s = PriorRound.fromLines(lines, maxRounds, maxProductChars);
            return new Read(s, true, null, lines.size());
        } catch (Exception e) {
            // 读不到 ≠ 没有上轮。这两种在回执里必须分开，否则读的人会以为「它没写过」。
            return new Read(PriorRound.Summary.none(), false,
                    "read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }

    /**
     * 读数。
     *
     * @param summary 上轮回顾（{@code ok=false} 时是「没有」而不是空）
     * @param ok      文件读到了没有（<b>false ≠ 没有上轮</b>：可能是日志文件还不存在）
     * @param error   读不到的原因（{@code ok=true} 时为 null）
     * @param linesScanned 扫了多少行（读数暴露，别让「读不到」藏在 0 里）
     */
    record Read(PriorRound.Summary summary, boolean ok, String error, int linesScanned) {

        /** 塞进工具回执的读数。 */
        java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("prior_round_readable", ok);
            if (!ok && error != null) {
                // ★ 「读不到」与「真的没有」分开写。这两种长得一样但含义完全相反：
                //   前者是观测面坏了，后者是事实。
                m.put("prior_round_error", error);
            }
            m.put("lines_scanned", linesScanned);
            m.put("prior_rounds", summary.rounds());
            m.put("prior_products", summary.products());
            m.put("prior_truncated", summary.truncated());
            m.put("prior_skipped_bad_lines", summary.skippedBadLines());
            // ★ 如实命名：这是「写了什么」，**不是**「后来成没成」。
            //   那个要等 acx.jsonl 的 run_finished 与 verified_count，那条链还不通。
            m.put("prior_includes_outcome", false);
            return m;
        }
    }
}
