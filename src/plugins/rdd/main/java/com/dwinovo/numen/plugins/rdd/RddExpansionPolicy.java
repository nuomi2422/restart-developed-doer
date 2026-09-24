package com.dwinovo.numen.plugins.rdd;

/**
 * 懒展开失败恢复策略（spec §9）——纯函数，阈值集中此处，驱动器只做编排，避免 God Object。
 *
 * <p>判定从严：展开失败 / 生成空规格（含"合法 JSON 但不可执行"被 parse 清空） / 注入被 core 拒
 * 一律计一次尝试；预算耗尽 escalate=停靠上报，绝不塞占位假二级、绝不伪造完成（§10）。
 */
final class RddExpansionPolicy {
    /** 同一当前一级的懒展开最多尝试次数（§9：重试 ≤3）。 */
    static final int MAX_EXPAND_ATTEMPTS = 3;
    /** 两次尝试之间最小间隔（ms）：给"换措辞重解"留出窗口，也避免每 tick 猛开 LLM。 */
    static final long EXPAND_COOLDOWN_MS = 5_000;

    private RddExpansionPolicy() {}

    /** 是否该现在发起一次展开：无在途、未 escalate、预算内、冷却已过。 */
    static boolean shouldFire(boolean inFlight, boolean escalated, int attempts,
                              long cooldownUntil, long now) {
        if (inFlight || escalated) {
            return false;
        }
        if (attempts >= MAX_EXPAND_ATTEMPTS) {
            return false;
        }
        return now >= cooldownUntil;
    }

    /** 尝试预算是否已耗尽（耗尽即升 escalate，停靠等主人换方向）。 */
    static boolean budgetExhausted(int attempts) {
        return attempts >= MAX_EXPAND_ATTEMPTS;
    }
}
