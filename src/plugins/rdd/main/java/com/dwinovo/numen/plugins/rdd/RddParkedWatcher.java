package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 停车守望：停车不等于可以静默死掉。
 *
 * <p>背景（实测教训）：停车（{@code gapParked}）本意是"别再自动重试烧 token"，
 * 但 tick 在 FAILED 分支就 return、卡死检测只在 RUNNING 跑——结果停车 = 永远没人拍醒，
 * AI 结束当前回合后无人喂目标即<b>永久静默</b>，且外部看不见（实测冻结 15 分钟零活动）。
 *
 * <p>这里补一层低频率守望：无进展后按 {@link #NUDGE_AFTER_SECONDS}（1 分钟 → 2 分钟 → 5 分钟）
 * 递进节奏再拍一次；拍满 {@link #MAX_PARKED_NUDGES} 次后只发可见事件
 * （{@code subtask_parked_silent}），把"需要人或外部介入"明确暴露出来，
 * 而不是让整条链无声冻结，也不再烧模型调用。
 */
final class RddParkedWatcher {

    /**
     * 催工节奏（单位：秒，1 次检测/秒）：无进展后依次等 1 分钟、2 分钟、5 分钟再拍醒一次。
     * 递进而非等长，避免刚停就烧模型；5 分钟仍无进展说明"这轮方向不行"，可以给足时间再催。
     * 数组用完 = 预算耗尽（等于 3 次催工），之后彻底静默，只发一次可见事件交人工/外层接手。
     */
    private static final int[] NUDGE_AFTER_SECONDS = {60, 120, 300};
    /** 同一停车二级最多再拍醒几次；之后只发可见事件，不再烧模型调用。 */
    private static final int MAX_PARKED_NUDGES = NUDGE_AFTER_SECONDS.length;
    /** "已放弃、需要外部介入"事件的兜底重播间隔（5 分钟）：不刷屏，也不让长期卡死静默。 */
    private static final long SILENT_REPEAT_MS = 5 * 60 * 1000L;

    private final RddStallWatcher stallWatcher;

    /** 停车守望状态（uuid→指纹/未变化计数/已拍醒次数）：停车也不能静默死掉。 */
    private final Map<UUID, ParkedWatch> parkedWatches = new ConcurrentHashMap<>();

    private record ParkedWatch(String subtaskId, String fingerprint, int unchanged, int nudges, long silentAt) {}

    RddParkedWatcher(RddStallWatcher stallWatcher) {
        this.stallWatcher = stallWatcher;
    }

    /** 对一个停车为 FAILED 的二级做低频守望：长期无进展就再拍一次，预算耗尽后只发事件。 */
    void watch(NumenPlayer ap, RddRuntime rt, Subtask current) {
        UUID uuid = ap.getUUID();
        // 抗抖动指纹：横跳/巡路打转不再每 tick 重置"无进展"窗口（否则催工永不触发，见 parkedFingerprint 注释）
        String fp = stallWatcher.parkedFingerprint(ap);
        ParkedWatch w = parkedWatches.get(uuid);
        if (w == null || !w.subtaskId().equals(current.id())) {
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, 0, 0L));
            return;
        }
        if (!w.fingerprint().equals(fp)) {
            // 仍有变化（AI 自己在推进 / 资产真被攒够）→ 重置窗口，不打扰
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, w.nudges(), 0L));
            return;
        }
        int unchanged = w.unchanged() + 1;
        if (w.nudges() >= MAX_PARKED_NUDGES) {
            // 预算耗尽：只报一次"需要外部介入"，之后不再拍醒、不再烧模型调用。
            // 2026-09-28 修刷屏：原来靠 `w.nudges() == MAX_PARKED_NUDGES` 判"首次"，
            // 但 nudges 到顶后不再变化 → 这个条件**每 tick 都成立** → 一次卡死刷出 90+ 条。
            // 现在用 silentAt 记录上次发布时间：首次立刻发，之后每 5 分钟兜底重播一次。
            long now = System.currentTimeMillis();
            boolean due = w.silentAt() == 0L || now - w.silentAt() >= SILENT_REPEAT_MS;
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, unchanged, w.nudges(),
                    due ? now : w.silentAt()));
            if (due) {
                RddMonitor.publish("subtask_parked_silent", Map.of(
                        "companionId", uuid.toString(), "subtask", current.id(),
                        "reason", "parked and unchanged after " + MAX_PARKED_NUDGES
                                + " nudges; needs external intervention"));
            }
            return;
        }
        int threshold = NUDGE_AFTER_SECONDS[w.nudges()];
        if (unchanged < threshold) {
            parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, unchanged, w.nudges(), w.silentAt()));
            return;
        }
        int nudged = w.nudges() + 1;
        parkedWatches.put(uuid, new ParkedWatch(current.id(), fp, 0, nudged, 0L));
        RddPlugin.nudge(uuid, "你的目标「" + current.description() + "」还在，但停车后一直没被判出进展。"
                + "先确认真实卡点：查看工具终态、附近资源、路径和装备；"
                + "方向不对就换一条路（换地点、换材料来源、换工具），不要原样重复。");
        RddMonitor.publish("subtask_parked_renudge", Map.of(
                "companionId", uuid.toString(), "subtask", current.id(),
                "nudge", nudged, "max", MAX_PARKED_NUDGES,
                "after_seconds", threshold,
                "next_after_seconds", nudged < MAX_PARKED_NUDGES
                        ? NUDGE_AFTER_SECONDS[nudged] : -1,
                "reason", "parked with no progress"));
    }

    /** 清掉某同伴的停车守望状态（二级变化/完成/跳过时调用）。 */
    void clear(UUID uuid) {
        parkedWatches.remove(uuid);
    }

    void clearAll() {
        parkedWatches.clear();
    }
}
