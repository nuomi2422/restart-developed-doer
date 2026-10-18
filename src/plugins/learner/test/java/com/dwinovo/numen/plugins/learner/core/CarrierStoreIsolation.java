package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.api.carrier.CarrierRuleStore;

import java.nio.file.Path;

/**
 * 测试隔离：把进程级生效链刷回「已安装、但没有任何已批准规则」。
 *
 * <p><b>为什么必须有这个</b>：{@link CarrierRuleStore} 的生效链是<b>进程级静态</b>
 * （{@code OVERRIDE}）。一个测试类批准过的规则会留给<b>后面所有测试类</b>。
 *
 * <p><b>2026-10-06 实测的假红</b>：{@code CarrierApprovalFlowTest} 批准了
 * {@code my_carrier} 并留在静态里；后续 {@code CarrierChainTest} /
 * {@code CarrierSignalB21Test} / {@code EnvSnapshotFormatTest} 读到它之后，
 * 短路行为整体改变 —— {@code Memo.assessCarrier} 把「可读快照、无可识别目标」
 * 判成 {@code UNKNOWN}（{@code Memo.java:119} 那条
 * {@code shortCircuited() && carry().isEmpty()} 分支被已批准规则的「空 fix」触发）。
 * ⇒ <b>单跑全绿、合跑 6 条红</b>。
 *
 * <p><b>为什么用 install(空目录) 而不是新增 reset()</b>：这样不需要给生产类
 * 加任何测试专用 API，而且走的是与 {@code LearnerPlugin.setup} 完全同一条路径
 * （装目录 → reload → 空已批准 → {@code OVERRIDE=null} → {@code effective()} 回到
 * 干净的 DEFAULT，且不带「未安装」标记）。
 */
final class CarrierStoreIsolation {

    private CarrierStoreIsolation() {
    }

    /** 装一个空的临时目录 ⇒ 生效链回到 DEFAULT。 */
    static void installEmpty() {
        CarrierRuleStore.install(Path.of(System.getProperty("java.io.tmpdir"),
                "carrier-isolation-" + System.nanoTime()));
    }
}
