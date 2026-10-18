package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;

/**
 * 产物投放口（{@code 38} v3 B16）。
 *
 * <p><b>用户 2026-10-01 原话</b>：「学习者你要留个接口，就是他可以自己写 ac，
 * 是我们这一波的话，<b>不包含 ac，暂时不要包含，降低复杂度</b>，你要要留个接口，懂我意思吧？」
 *
 * <p><b>⚠️ 反「假红线」的硬要求</b>：接口在、永远没人实现 = 门禁形同虚设
 * （这是 GPT-6 审稿点名过的风险类型）。所以「没实现」必须<b>显式抛错，不许静默 no-op</b> ——
 * 「调了却没实现」要变成一眼看得见的失败。
 *
 * <p><b>2026-10-05（B6/S2）现状</b>：接口<b>已经有真实现了</b>，见 {@link OutboxArtifactSink}。
 * 它把三种载荷写进共享投递箱；<b>AC 已由下游插件真实消费</b>
 * （acx 插件的 {@code AcxArtifactAdopter}，发布为 {@code GENERATED}，绝不自动上线）。
 * 经验这一位<b>故意仍抛错</b> —— 经验库有既有出口 {@code experience_learn}，
 * 另开一条写库通道就是两套并存必然漂移（外层 AGENTS.md 记的正是这类事故）。
 */
public interface ArtifactSink {

    /** 投放一条经验。**故意保持抛错**：经验库归既有 {@code experience_learn} 管。 */
    void publishExperience(String experienceId, Map<String, Object> experience);

    /**
     * 投递一份 AC 脚本。
     *
     * @param scriptName 脚本名
     * @param scriptBody 脚本体
     * @return 落地后的可引用标识
     */
    String publishAcScript(String scriptName, String scriptBody);

    /** 投放一份携带器内容。 */
    void publishCarrier(String carrierName, String content);

    /** 这个 sink 现在能不能用（有投递箱时为 true）。 */
    boolean available();
}