package com.dwinovo.numen.acx.core;

import java.util.List;
import java.util.Map;

/**
 * 「实质进展」判定。搬自 DD 版 {@code AcRunner.PROGRESS_FIELDS}（:559-562）与
 * {@code hasRealProgress}（:568-576）。
 *
 * <p>这是 {@code while} 停滞检测的地基：没有它，循环只能等 {@code max_iters} 或 wall-clock 超时被杀，
 * 那正是 2026-08-04 定为铁律要避免的「等超时被杀」。</p>
 *
 * <p><b>保守策略</b>（DD 原样）：没有进度字段、或字段为 0、或 null/空 → 都算「无进展」。
 * 宁可误判成停滞提前优雅收手，也不要把一个卡住的循环继续烧下去。</p>
 */
public final class AcxProgress {

    public static final List<String> FIELDS = List.of(
            "mined", "found", "collected", "placed", "smelted",
            "gathered", "moved", "removed", "progress", "count", "gained");

    private AcxProgress() { }

    /** 任一进度字段 &gt; 0（或为 true）→ 本轮有实质进展。 */
    public static boolean hasRealProgress(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        for (String f : FIELDS) {
            Object v = data.get(f);
            if (v instanceof Number n && n.doubleValue() > 0) {
                return true;
            }
            if (v instanceof Boolean b && b) {
                return true;
            }
        }
        return false;
    }

    /** 抽取输出里的进度字段，用于 PAUSED 记录与消息里的「进度: mined=5」摘要。 */
    public static Map<String, Object> extractProgress(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String f : FIELDS) {
            Object v = data.get(f);
            if (v instanceof Number n && n.doubleValue() > 0) {
                out.put(f, n);
            } else if (v instanceof Boolean b && b) {
                out.put(f, b);
            }
        }
        return out;
    }

    /** 渲染成 {@code mined=5, count=3} 形式；空则返回「无」。 */
    public static String renderSummary(Map<String, Object> progress) {
        if (progress == null || progress.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : progress.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}