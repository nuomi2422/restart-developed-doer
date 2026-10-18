package com.dwinovo.numen.rdd.api;

public enum SubtaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, STALLED, COOLDOWN, INVALIDATED, SKIPPED,
    /**
     * 暂停：规划器判定这个二级<b>暂时不要开始，但留着、以后还能开</b>。
     *
     * <p>与相邻两个状态的区别就是本枚举存在的理由：
     * {@link #SKIPPED} 是<b>放弃</b>（立刻推进到下一个，永不回来）；
     * {@link #FAILED} 是<b>做不到</b>（进重试/能力缺口/失败升级的循环）；
     * 暂停是<b>「先放着」</b>——不推进、不失败、不消耗重试预算，
     * 条件变了或规划器改主意时 {@code resumeFromPaused} 可以原地开回来。
     *
     * <p>监督天然不碰它：RddDetector 的推进路径以 {@code currentSubtaskStatus() != RUNNING} 为准，
     * PAUSED 下不派工、不拍醒、不判卡死，这正是"暂停"应有的行为。
     */
    PAUSED
}
