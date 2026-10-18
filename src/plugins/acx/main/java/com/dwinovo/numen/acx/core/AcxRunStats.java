package com.dwinovo.numen.acx.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;

/**
 * 单个 AC 版本的运行统计（跨版本聚合也用同一个类）。
 *
 * <p>这是用户架构里「每版本独立运行标记 + 质量统计」的落点：跑过几次、成功/暂停/失败/超时各几次、
 * 平均与最长耗时、几次因停滞优雅收手、最后状态与最后原因。</p>
 */
public final class AcxRunStats {

    private int runs;
    private int success;
    private int failed;
    private int paused;
    private int timedOut;
    private int stagnantRuns;
    private long totalElapsedMs;
    private long maxElapsedMs;
    private long lastRunAt;
    private String lastStatus = "";
    private String lastPausedReason = "";
    private String lastFailedStep = "";

    void accept(AcxRunRecord r) {
        if (r == null) {
            return;
        }
        runs++;
        AcxStatus st = r.status();
        if (st == AcxStatus.SUCCESS) {
            success++;
        } else if (st == AcxStatus.PAUSED) {
            paused++;
        } else if (st == AcxStatus.FAIL) {
            failed++;
        } else if (st == AcxStatus.TIMEOUT) {
            timedOut++;
        }
        if (r.stagnantStep() != null) {
            stagnantRuns++;
        }
        totalElapsedMs += Math.max(0, r.elapsedMs());
        maxElapsedMs = Math.max(maxElapsedMs, r.elapsedMs());
        if (r.timestamp() >= lastRunAt) {
            lastRunAt = r.timestamp();
            lastStatus = st == null ? "" : st.name();
            lastPausedReason = r.pausedReason() == null ? "" : r.pausedReason();
            lastFailedStep = r.failedStep() == null ? "" : r.failedStep();
        }
    }

    /** 把另一份统计（通常是单版本）并进来，供跨版本聚合。 */
    void merge(AcxRunStats o) {
        if (o == null) {
            return;
        }
        runs += o.runs;
        success += o.success;
        failed += o.failed;
        paused += o.paused;
        timedOut += o.timedOut;
        stagnantRuns += o.stagnantRuns;
        totalElapsedMs += o.totalElapsedMs;
        maxElapsedMs = Math.max(maxElapsedMs, o.maxElapsedMs);
        if (o.lastRunAt >= lastRunAt) {
            lastRunAt = o.lastRunAt;
            lastStatus = o.lastStatus;
            lastPausedReason = o.lastPausedReason;
            lastFailedStep = o.lastFailedStep;
        }
    }

    public int runs() { return runs; }

    public int success() { return success; }

    public int failed() { return failed; }

    public int paused() { return paused; }

    public int timedOut() { return timedOut; }

    public int stagnantRuns() { return stagnantRuns; }

    public long totalElapsedMs() { return totalElapsedMs; }

    public long maxElapsedMs() { return maxElapsedMs; }

    public long lastRunAt() { return lastRunAt; }

    public String lastStatus() { return lastStatus; }

    public String lastPausedReason() { return lastPausedReason; }

    public String lastFailedStep() { return lastFailedStep; }

    public double successRate() {
        return runs == 0 ? 0.0 : (double) success / runs;
    }

    public long avgElapsedMs() {
        return runs == 0 ? 0 : totalElapsedMs / runs;
    }

    /** 一行人类可读摘要（进日志 / acx_status 门面用）。 */
    public String summary() {
        return runs + " 次：成功 " + success + " / 暂停 " + paused + " / 失败 " + failed
                + " / 超时 " + timedOut
                + "，成功率 " + Math.round(successRate() * 100) + "%"
                + "，平均 " + avgElapsedMs() + "ms，停滞 " + stagnantRuns + " 次"
                + (lastStatus.isEmpty() ? "" : "，最后 " + lastStatus);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", runs);
        m.put("success", success);
        m.put("failed", failed);
        m.put("paused", paused);
        m.put("timed_out", timedOut);
        m.put("stagnant_runs", stagnantRuns);
        m.put("total_elapsed_ms", totalElapsedMs);
        m.put("max_elapsed_ms", maxElapsedMs);
        m.put("avg_elapsed_ms", avgElapsedMs());
        m.put("success_rate", successRate());
        m.put("last_run_at", lastRunAt);
        m.put("last_status", lastStatus);
        m.put("last_paused_reason", lastPausedReason);
        m.put("last_failed_step", lastFailedStep);
        return m;
    }

    static AcxRunStats fromMap(Map<String, Object> m) {
        AcxRunStats s = new AcxRunStats();
        if (m == null) {
            return s;
        }
        s.runs = (int) num(m.get("runs"));
        s.success = (int) num(m.get("success"));
        s.failed = (int) num(m.get("failed"));
        s.paused = (int) num(m.get("paused"));
        s.timedOut = (int) num(m.get("timed_out"));
        s.stagnantRuns = (int) num(m.get("stagnant_runs"));
        s.totalElapsedMs = num(m.get("total_elapsed_ms"));
        s.maxElapsedMs = num(m.get("max_elapsed_ms"));
        s.lastRunAt = num(m.get("last_run_at"));
        s.lastStatus = m.get("last_status") == null ? "" : String.valueOf(m.get("last_status"));
        s.lastPausedReason = m.get("last_paused_reason") == null ? "" : String.valueOf(m.get("last_paused_reason"));
        s.lastFailedStep = m.get("last_failed_step") == null ? "" : String.valueOf(m.get("last_failed_step"));
        return s;
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }
}
