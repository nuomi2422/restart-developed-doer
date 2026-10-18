package com.dwinovo.numen.acx.api;

import java.util.Map;

/**
 * ═══ INTERFACE CONTRACT: ACX-V1 实证验证 ═══
 * <p>语义：积木声称「挖了 / 放上了 / 捡到了」之后，用真实世界读数对撞。
 * 对不上就是<b>假成功</b>，不能记 SUCCESS。</p>
 * <p>方向：积木执行完 → {@code AcxVerifier} 对撞 → 决定 {@link AcxStepOutcome}</p>
 * <p>消费：执行器（每步成功后调用）；默认实现可放宿主</p>
 * <p>违反：假成功（工具报告成功但世界没变）→ 上游规划器据此认为资源到手，后续步骤连环空跑</p>
 * <p>参考：DD 版 {@code brain/ac/VerificationHelper.java}（挖 / 放两种纯函数）</p>
 */
@FunctionalInterface
public interface AcxVerifier {

    VerifyResult verify(String blockName, Map<String, Object> params, Map<String, Object> claimedOutput);

    /**
     * @param verified {@code true} 表示世界读数与声明一致
     * @param reason   {@code null} 时视为通过；非空时应写进事件 detail 与失败消息
     */
    record VerifyResult(boolean verified, String reason) {
        public static VerifyResult pass() {
            return new VerifyResult(true, null);
        }

        public static VerifyResult reject(String reason) {
            return new VerifyResult(false, reason == null ? "verifier rejected" : reason);
        }
    }

    /** 没有宿主验证器时用：全部放行，但 {@code reason} 会让执行器补发一条 VERIFIER_ABSENT 事件。 */
    static AcxVerifier permissive() {
        return (blockName, params, claimedOutput) -> VerifyResult.pass();
    }
}