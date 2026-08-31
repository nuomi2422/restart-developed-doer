package com.dwinovo.numen.experience.api;

/**
 * 经验成熟度四级（旧 DD 语义）：写入不等于学会，单次观察不等于可信任规则。
 *
 * <ul>
 *   <li>{@link #OBSERVED} — 观察到/被教过，尚未尝试。</li>
 *   <li>{@link #ATTEMPTED} — 尝试过但失败/不适用，暂时不能当作可靠规则。</li>
 *   <li>{@link #VERIFIED} — 真实环境中确认成功过。</li>
 *   <li>{@link #GENERALIZED} — 跨场景反复确认成功，接近真正的“学会”。</li>
 * </ul>
 *
 * <p>升级只在 {@code recordEvidence(success=true)} 时发生；失败只累加反例、最多把
 * OBSERVED 推到 ATTEMPTED，不降级已确认的经验。
 */
public enum ExperienceMaturity {
    OBSERVED(0),
    ATTEMPTED(1),
    VERIFIED(2),
    GENERALIZED(3);

    private final int level;

    ExperienceMaturity(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /** {@code candidate} 是否达到 {@code min} 的成熟度；{@code min == null} 时恒通过。 */
    public static boolean atLeast(ExperienceMaturity candidate, ExperienceMaturity min) {
        return min == null || candidate.level() >= min.level();
    }

    /** 取两者中更成熟的一档。 */
    public static ExperienceMaturity max(ExperienceMaturity a, ExperienceMaturity b) {
        return a.level() >= b.level() ? a : b;
    }
}
