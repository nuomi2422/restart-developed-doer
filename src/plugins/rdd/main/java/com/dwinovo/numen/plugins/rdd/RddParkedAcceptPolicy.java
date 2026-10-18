package com.dwinovo.numen.plugins.rdd;

/**
 * 停车态「条件已达成」裁决（纯逻辑，便于单测；2026-09-28）。
 *
 * <p>背景（实机教训）：RddDetector 在 {@code primaryStatus != ACTIVE} 的停车态
 * （WAITING / REPLANNING / FAILED）会提前 {@code return}，而「资产提前触发验收
 * （EarlyAchievement）」整段逻辑都挂在 {@code ACTIVE} 之后 —— 于是能力在、够不着。
 * 真实表现：食物条件早就满足（小麦 19 >= 12，{@code cond=true}），链却一直
 * {@code REPLANNING/FAILED} 原地不动，AI 继续白干（去种不会长的麦子、挪铁锭）。
 *
 * <p>裁决规则：停车态且当前二级的硬编码条件**此刻已满足**时，不该继续罚站，
 * 应把一级退回正常流程（PENDING -> 依赖门 -> ACTIVE），让既有的验收/连跳逻辑接管。
 *
 * <p>为什么只对硬编码条件生效：软判定（模型判据）需要 AI 回合才能出结论，
 * 不能在没有新证据时替它宣布完成 —— 宁可多等一轮，不伪造完成。
 *
 * <p>为什么需要 {@code alreadyResumedForThisPrimary} 防抖：resume 会把本级二级全置
 * PENDING 并回退游标，若下一 tick 又判定"已达成"就形成 停车->回退->再停车 的乒乓，
 * 白烧 CPU 与事件。同一级只自动回退一次，之后交给停车守望催工与人工。
 */
final class RddParkedAcceptPolicy {

    private RddParkedAcceptPolicy() {
    }

    /**
     * @param replanning                  当前一级是否处于 REPLANNING（唯一可自动回退的停车态）
     * @param hasCurrentSubtask           当前二级是否存在
     * @param hardCoded                   当前二级是否为硬编码条件（可本地判定达成）
     * @param conditionMet                此刻真实背包是否已满足该二级条件
     * @param alreadyResumedForThisPrimary 本级是否已经因"已达成"自动回退过（防乒乓）
     * @return 是否应当 {@code resumeFromReplanning()} 退回正常流程
     */
    static boolean shouldResume(boolean replanning, boolean hasCurrentSubtask, boolean hardCoded,
                                boolean conditionMet, boolean alreadyResumedForThisPrimary) {
        return replanning && hasCurrentSubtask && hardCoded && conditionMet && !alreadyResumedForThisPrimary;
    }
}
