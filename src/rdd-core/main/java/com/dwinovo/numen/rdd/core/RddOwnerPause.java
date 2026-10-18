package com.dwinovo.numen.rdd.core;

/**
 * 主人下的暂停 / 恢复（第一方控制面，2026-09-30 深审 R04/codex P1-2）。
 *
 * <p><b>为什么单独一个类</b>：RDD 内部所有暂停都来自士兵的 {@code report_task_concern(PAUSE)}，
 * 语义是「我做不了，等条件变」——该到期复评、该被催。
 * 主人说的「原地待命」是<b>命令</b>：永不过期、绝不自动恢复、执行层的建议也推不翻它。
 * 两种意图混在同一个字符串前缀里迟早出事，所以**入口分开**：
 * 这里写入的原因带 {@link TaskChain#OWNER_PAUSE_PREFIX}，
 * {@code RddDetector.isOwnerPause} 据此给它们不同的治理。
 *
 * <p>纯 JVM，无 MC 依赖，可单测。
 */
public final class RddOwnerPause {

    private RddOwnerPause() {}

    /**
     * 主人要求原地待命：把当前二级按住。
     *
     * @return 成功 true；链不可用 / 没有可暂停的当前二级 / 已经是主人在暂停 → false（不抛）
     */
    public static boolean pause(RddRuntime rt, String note) {
        if (rt == null) return false;
        TaskChain chain = rt.chain();
        var cur = chain.currentSubtask();
        if (cur == null) return false;
        try {
            if (chain.currentSubtaskStatus() == com.dwinovo.numen.rdd.api.SubtaskStatus.PAUSED) {
                // 已在暂停：只刷新原因，不动状态（重复点"待命"不该报错）
                chain.resumeFromPaused(cur.id());
            }
            String reason = TaskChain.OWNER_PAUSE_REASON
                    + (note == null || note.isBlank() ? "" : " (" + note.trim() + ")");
            chain.pauseSubtask(cur.id(), reason);
            // 计时也要重起：主人是刚刚下的令，不该继承士兵上次暂停的进度
            rt.clearPaused(cur.id());
            return true;
        } catch (RuntimeException notPausable) {
            return false;   // 已 COMPLETED / 链不在 ACTIVE 等：如实失败，不伪造成功
        }
    }

    /**
     * 主人解除待命：只有主人能开。
     *
     * @return true = 确实从暂停回到了运行
     */
    public static boolean resume(RddRuntime rt) {
        if (rt == null) return false;
        TaskChain chain = rt.chain();
        var cur = chain.currentSubtask();
        if (cur == null) return false;
        if (chain.currentSubtaskStatus() != com.dwinovo.numen.rdd.api.SubtaskStatus.PAUSED) {
            return false;
        }
        try {
            chain.resumeFromPaused(cur.id());
            rt.clearPaused(cur.id());
            return true;
        } catch (RuntimeException notResumable) {
            return false;
        }
    }
}
