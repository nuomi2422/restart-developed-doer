package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.LocalRepairTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * \u652f\u7ebf\u4efb\u52a1\uff08\u76f4\u7ebf\u4efb\u52a1 / Local Repair Task\uff09\u5206\u53d1\u5668 \u2014\u2014 \u63d2\u4ef6\u4fa7\u63a5\u7ebf\u3002
 *
 * <p>\u3010\u7528\u6237\u67b6\u6784\u6982\u5ff5 1/4 \u7684\u63a5\u7ebf\u3011\u6b7b\u4ea1/\u7269\u54c1\u4e22\u5931\u7b49\u7a81\u53d1\u65f6\uff0c\u751f\u6210\u4e00\u6761"\u4e0d\u6574\u94fe\u91cd\u89c4\u5212"\u7684\u652f\u7ebf\u4efb\u52a1\uff0c
 * \u7ecf\u4e2d\u65ad\u98ce\u66b4\u62a4\u680f\u5224\u5b9a\u540e\uff0c\u7528 nudge \u6ce8\u5165\u6267\u884c\u4f53\uff1a"\u5148\u5904\u7406\u773c\u524d\u95ee\u9898\uff0c\u522b\u52a8\u4e3b\u94fe"\u3002
 * \u7eaf\u903b\u8f91\u5728 rdd-core \u7684 {@link LocalRepairTask}\uff1b\u672c\u7c7b\u53ea\u505a\u5bbf\u4e3b\u4fa7\u72b6\u6001\u4e0e\u6295\u653e\u3002
 *
 * <p>\u4e0d\u6539 TaskChain \u6838\u5fc3\uff1b\u4e0d\u6d3e\u5de5\uff08nudge \u662f\u63d0\u793a\uff0c\u6267\u884c\u4f53\u81ea\u51b3\uff09\uff1b\u7ea2\u7ebf\u4e0d\u52a8\u3002
 */
final class RddRepairDispatch {

    private RddRepairDispatch() {}

    /** \u6bcf\u540c\u4f34\u7684\u6700\u8fd1\u652f\u7ebf\u4efb\u52a1\uff08\u89c2\u6d4b/\u751f\u6210\u53bb\u91cd\u7528\uff09\u3002 */
    private static final Map<UUID, LocalRepairTask.Task> LAST_REPAIR = new ConcurrentHashMap<>();
    /** \u6bcf\u540c\u4f34\u7684\u62a4\u680f\u8ffd\u8e2a\uff08\u51b7\u5374/\u9891\u7387/\u8fde\u7eed\u5931\u8d25\uff09\u3002 */
    private static final Map<UUID, LocalRepairTask.Guard.Tracker> TRACKERS = new ConcurrentHashMap<>();
    private static final AtomicLong ID_SEQ = new AtomicLong();
    private static final LocalRepairTask.Guard GUARD = LocalRepairTask.Guard.defaults();

    /** F6：当前支线对应的死亡批次 seq（结账的身份，不是"最近那条"）。 */
    private static final Map<UUID, Integer> OPEN_DEATH_SEQ = new ConcurrentHashMap<>();

    /** 非重复死亡的支线步骤（RL-7：先捡包后取备用，掉落物限时）。 */
    private static final String DEFAULT_STEPS =
            "先去世亡点 {at} 捡回掉落物（限时！约 5 分钟消失）；再取备用装备；然后回主链";
    private static final String DEFAULT_GOAL =
            "死亡支线：捡包限时优先（先捡后取备用），不整链重规划；完成后回主链";

    /** F4a 重复死亡：先活着离开，再回来捡。倒计时照样必须带上（R07）。 */
    private static final String REPEAT_DEATH_STEPS =
            "① 立刻离开 {at} 附近（刚在那里死了第二次）② 回基地补血/吃饱/换装备 ③ 再回来捡掉落物（限时）";
    private static final String REPEAT_DEATH_GOAL =
            "重复死亡支线：先撤离并补给，再限时回收掉落物；宁可不捡也不要再死一次";

    /**
     * 生成并（经护栏）投放一条死亡支线任务。 返回 true = 已投放（nudge 注入）。
     * 死亡触发优先级 CRITICAL（护栏放行）。
     *
     * <p><b>顺序是"先捡包、后取备用"（2026-09-29 用户实测纠正）</b>：
     * 掉落物约 <b>5 分钟</b>就 despawn，而"先回基地取备用装备"很容易把这段限时窗口耗光——
     * 那样就只捡回一部分、其余刷没了（用户原话：剪了，只剪了一部分，过 5 分钟有些就没了）。
     * 所以捡包**限时优先**，备用装备排后面；且必须把**死亡坐标**给它，否则它不知道去哪捡。
     *
     * <p><b>F4a 重复死亡例外</b>：同一点短时间内又死一次（实机 #3→#4 只隔 62 秒、坐标差 1 格）时，
     * "先回去捡"恰恰是送命的那一步 —— 她身上什么都没有，回到杀过她两次的地方再死一次。
     * 此时顺序改成「先撤离 + 补给 → 再回来捡」，且**仍然把剩余秒数带进文案**
     *（R07 已冻结：倒计时必须在它眼前）。
     *
     * @param deathAt 死亡点坐标文本（如 "425, 75, -298"），可空
     * @param dropTimeline 死亡台账渲染出的整张表（每次死亡一行 + 各自还剩多少秒），可空
     * @param repeatDeath 同一点短时间内又死了一次（F4a）
     * @param death 本次死亡记录（把批次身份带进支线；可空 = 退化为不带 seq 的旧路径）
     */
    static boolean onDeath(UUID companionId, String goalId, String deathAt, String dropTimeline,
                           boolean repeatDeath,
                           com.dwinovo.numen.rdd.core.RddDeathLedger.Death death) {
        if (companionId == null) return false;
        RddPlugin.ensureLedgerLoaded(companionId);
        long now = RddInstrumentation.currentGameTimeTicks();
        LocalRepairTask.Priority pri = LocalRepairTask.defaultPriority(LocalRepairTask.Trigger.DEATH);
        LocalRepairTask.Guard.Tracker tr = TRACKERS.computeIfAbsent(companionId, k -> new LocalRepairTask.Guard.Tracker());
        LocalRepairTask.Guard.Decision d = GUARD.evaluate(tr, LocalRepairTask.Trigger.DEATH, now, pri);
        if (d != LocalRepairTask.Guard.Decision.ALLOW) {
            RddMonitor.publish("repair_task_suppressed", Map.of(
                    "companionId", companionId.toString(), "trigger", "DEATH", "decision", d.name()));
            return false;
        }
        String at = (deathAt == null || deathAt.isBlank()) ? "死亡点" : deathAt;
        // 台账原样拼进 nudge：倒计时必须在**她眼前**，规划器看不到就等于没有。
        String timeline = (dropTimeline == null || dropTimeline.isBlank()) ? "" : ("\n" + dropTimeline);
        String id = "repair-" + companionId + "-" + ID_SEQ.incrementAndGet();
        String parent = (goalId == null || goalId.isBlank()) ? ("goal-" + companionId) : goalId;
        LocalRepairTask.Task task = LocalRepairTask.Task.of(id, parent, "death-event",
                LocalRepairTask.Trigger.DEATH, pri, 5 * 60 * 20,
                LocalRepairTask.ResumePolicy.RESEARCH_TARGET,
                repeatDeath ? REPEAT_DEATH_STEPS.replace("{at}", at) : DEFAULT_STEPS.replace("{at}", at),
                "ESCALATE",
                repeatDeath ? REPEAT_DEATH_GOAL : DEFAULT_GOAL);
        LAST_REPAIR.put(companionId, task);
        // 批次身份跟着支线走：回收判据必须知道"结账的是哪一次死亡"（F6）。
        if (death != null) {
            OPEN_DEATH_SEQ.put(companionId, death.seq());
        }
        tr.recordFire(LocalRepairTask.Trigger.DEATH, now);
        RddMonitor.publish("repair_task_dispatched", Map.of(
                "companionId", companionId.toString(), "repairId", id, "priority", pri.name(),
                "deathAt", at, "dropTimeline", dropTimeline == null ? "" : dropTimeline,
                "deathSeq", death == null ? -1 : death.seq(),
                "trackedItemKinds", death == null ? 0 : death.lostItems().size(),
                "repeatDeath", repeatDeath));
        if (repeatDeath) {
            RddPlugin.nudge(companionId, "[支线任务/死亡] 你**刚在同一个地方又死了一次**。"
                    + "回去拿掉落物不是现在该做的事：你现在身上什么都没有，回去只会被再杀一次。"
                    + "\n先做这三件事：① 立刻离开 " + at + " 附近（那地方已经杀了你两次）"
                    + " ② 回基地/安全处补血、吃饱、换掉破损装备 "
                    + " ③ 再回来捡掉落物（下面有时间表）" + timeline
                    + "\n如果装备掉得太惨、这一趟不值得冒险，就**明确说出来**：宁可少捡，也不要再死一次。"
                    + "\n不要为这件事重规划整条主链。");
            return true;
        }
        RddPlugin.nudge(companionId, "[支线任务/死亡] 你刚死一次。掉落物在 "
                + at + " 附近——**注意：掉落物约 5 分钟就会消失**。"
                + "所以顺序是：先立刻回 " + at + " 把掉落物捡回来（限时优先），"
                + "再去取备用装备，最后回主链继续。不要为这件事重规划整条主链。" + timeline);
        return true;
    }

    /** \u652f\u7ebf\u6210\u529f\uff1a\u6e05\u8fde\u7eed\u5931\u8d25\u8ba1\u6570\u5e76\u79fb\u9664\u3002 */
    static void onRepairSuccess(UUID companionId) {
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordSuccess(LocalRepairTask.Trigger.DEATH);
        LocalRepairTask.Task done = LAST_REPAIR.remove(companionId);
        if (done != null) {
            RddMonitor.publish("repair_task_succeeded", Map.of(
                    "companionId", companionId.toString(), "repairId", done.id(),
                    "deathSeq", openDeathSeq(companionId),
                    "reason", "every tracked drop of THIS death batch is back in the real inventory"));
            // F6：结账必须带**本次支线对应的 deathSeq**，不能再用 -1（"最近那条"）。
            // -1 的真实危害：连死两次时，上一批的掉落物回来会把这一批的支线结掉。
            int seq = openDeathSeq(companionId);
            if (seq >= 0) {
                OPEN_DEATH_SEQ.remove(companionId);
                // 深审 R08：把"这次死亡已回收"落到台账，规划器才不会继续把它当待办。
                if (com.dwinovo.numen.rdd.core.RddDeathLedger.confirmRecovered(companionId, seq,
                        "all tracked drops of this batch observed back in inventory")) {
                    RddPlugin.saveLedger(companionId);   // F2：状态变了就落盘
                }
            }
        }
    }

    /** 当前支线对应的死亡批次 seq；没有（未派发/已结账）返回 -1。 */
    static int openDeathSeq(UUID companionId) {
        return companionId == null ? -1 : OPEN_DEATH_SEQ.getOrDefault(companionId, -1);
    }

    /**
     * F6：按批次核对掉落是否真的捡回来了。
     *
     * <p>只认<b>当前支线对应的那一次死亡</b>，逐项按该批次的 {@code lostItems} 核对。
     * 旧判据（任意一件 LOST 物品现在 &gt;0）会让 33 格掉落里捡回 1 格就判整条支线成功。
     *
     * @param counts 宿主刚扫描到的真实背包
     * @return satisfied=true 时才允许调用 {@link #onRepairSuccess(UUID)}；
     *         matched=false 表示批次对不上（不该结账）
     */
    static boolean checkBatchRecovered(UUID companionId, java.util.Map<String, Integer> counts) {
        int seq = openDeathSeq(companionId);
        if (seq < 0) return false;
        return com.dwinovo.numen.rdd.core.RddDeathLedger.checkBatchRecovery(companionId, seq, counts).satisfied();
    }

    /**
     * 部分回收：发进度事件，<b>不</b>结账。
     *
     * <p>进度必须可见，否则"她捡了 3/10 却没人告诉她还差多少"和"完全没捡"长得一模一样。
     */
    static void reportBatchProgress(UUID companionId, java.util.Map<String, Integer> counts) {
        int seq = openDeathSeq(companionId);
        if (seq < 0) return;
        var r = com.dwinovo.numen.rdd.core.RddDeathLedger.checkBatchRecovery(companionId, seq, counts);
        if (!r.matched() || r.tracked() <= 0 || !r.anyBack()) return;
        RddMonitor.publish("repair_task_progress", Map.of(
                "companionId", companionId.toString(),
                "deathSeq", r.seq(),
                "trackedItemKinds", r.tracked(),
                "outstandingItemKinds", r.outstanding(),
                "fraction", r.fraction(),
                "note", "partial pickup; the repair branch is NOT closed until every tracked drop is back"));
    }

    /**
     * 死亡掉落是否已经被观测到回收（决定死亡支线算不算完成）。
     *
     * <p>深审 R08（codex）：旧实现 \`onRepairSuccess\` **零生产调用**，支线派出去就没人管，
     * \`Guard.Tracker\` 的成功复位不可达 → "连续失败"只增不减，迟早把后续死亡支线全压掉。
     * 接在 \`RddDetector\` 观测到「背包重新出现死亡时丢的资产」处 —— 那是"捡回来了"唯一可靠的证据。
     */
    static boolean dropObservedRecovered(UUID companionId) {
        return !com.dwinovo.numen.rdd.core.RddDeathLedger.recovered(companionId).isEmpty();
    }

    /** \u652f\u7ebf\u5931\u8d25\uff1a\u7d2f\u8ba1\u8fde\u7eed\u5931\u8d25\uff08\u8fbe\u9608\u503c\u89e6\u53d1\u5347\u7ea7\u91cd\u89c4\u5212\uff09\u3002 */
    static void onRepairFailure(UUID companionId) {
        LocalRepairTask.Guard.Tracker tr = TRACKERS.get(companionId);
        if (tr != null) tr.recordFailure(LocalRepairTask.Trigger.DEATH);
    }

    static LocalRepairTask.Task lastRepair(UUID companionId) { return LAST_REPAIR.get(companionId); }

    static void remove(UUID companionId) {
        LAST_REPAIR.remove(companionId);
        TRACKERS.remove(companionId);
        OPEN_DEATH_SEQ.remove(companionId);
    }
}
