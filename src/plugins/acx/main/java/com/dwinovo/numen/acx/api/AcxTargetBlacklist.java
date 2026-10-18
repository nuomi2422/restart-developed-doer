package com.dwinovo.numen.acx.api;

import java.util.Map;

/**
 * ═══ INTERFACE CONTRACT: ACX-B1 失败目标拉黑 ═══
 * <p>语义：一个 {@code while} 子步失败后，把它指向的目标拉黑，让下一轮扫描换目标，
 * 而不是让整个 AC 中止、逼 AI 反复重调（那是在烧 token）。</p>
 * <p>方向：执行器 → 宿主扫描器</p>
 * <p>消费：扫描类积木（DD 版是硬编码直调 {@code ScanBlocksAction.blacklistFailedTarget(x,y,z)}）</p>
 * <p>违反：ACX core 会反向依赖具体扫描积木 → core 不再是纯 JVM</p>
 */
@FunctionalInterface
public interface AcxTargetBlacklist {

    /**
     * @param failedParams 失败那一步的（已解析）参数。宿主自己判断哪些字段是坐标。
     */
    void blacklist(Map<String, Object> failedParams);

    static AcxTargetBlacklist noop() {
        return params -> { };
    }
}