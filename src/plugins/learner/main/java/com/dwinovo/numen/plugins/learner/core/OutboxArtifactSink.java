package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;

/**
 * {@link ArtifactSink} 基于 {@link ArtifactOutbox} 的实现（B6/S2，2026-10-05）。
 *
 * <p><b>为什么要它</b>：B16 留的 {@code ArtifactSink} 一直挂着
 * {@link UnsupportedArtifactSink}，而复审工具改用投递箱直投 —— 结果是
 * <b>两个投放口并存、只有一处在用</b>。那正是 AGENTS.md 里反复记的「两套并存必然漂移」。
 * 现在统一到一个实现上：接口不变，实现换成真家伙。
 *
 * <p><b>它做的是「落地」，不是「生效」</b>：写进投递箱只保证<b>产物不会丢</b>。
 * AC 已由 acx 插件真实消费；<b>携带器与自编译请求目前只有落点、没有下游</b>，
 * 状态里会如实体现（见 {@link ArtifactOutbox#stats()}），不假装已生效。
 *
 * <p><b>经验这一位故意抛错</b>：经验库有既有出口 {@code experience_learn}，
 * 这里再写一遍就是两条写库路径 —— 那是「回执说成功、实际写错地方」的经典温床。
 */
public final class OutboxArtifactSink implements ArtifactSink {

    /**
     * 经验这一位不发到投递箱的原因。引用既有出口，不另造第二条写库通道。
     */
    public static final String EXPERIENCE_DELEGATED_MSG =
            "学习者不自己写经验库：经验有既有出口 experience_learn，"
                    + "这里另开一条就是两套并存必然漂移（外层 AGENTS.md 记的正是这类事故）。"
                    + "要写经验请让 AI 调 experience_learn。";

    /**
     * B16 的签名没有同伴参数，而投递箱<b>强制</b>按同伴隔离（{@code companionId} 不许 null）。
     * 这里用固定哨兵 UUID 而不是 null/空串 —— 显式值能让下游一眼看出
     * 「这批不是同伴级来源」，猜不出它从哪来。
     */
    private static final java.util.UUID NO_COMPANION =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ArtifactOutbox outbox;

    public OutboxArtifactSink(ArtifactOutbox outbox) {
        this.outbox = outbox;
    }

    /** 供观测/回执引用「为什么经验不走这里」，不要另写一份文案。 */
    public static String experienceDelegatedReason() {
        return EXPERIENCE_DELEGATED_MSG;
    }

    @Override
    public void publishExperience(String experienceId, Map<String, Object> experience) {
        throw new UnsupportedOperationException(EXPERIENCE_DELEGATED_MSG);
    }

    /**
     * 投递一份 AC 脚本 → 进投递箱等 acx 插件采纳。
     *
     * <p><b>只落地，不上线</b>：下游会把它发布成 {@code GENERATED}，
     * 仍要人工 approve 才会真跑。
     *
     * @return 落地后的可引用标识（投递记录 id）
     */
    @Override
    public String publishAcScript(String scriptName, String scriptBody) {
        ArtifactOutbox.Delivery d = outbox.submit(
                ArtifactOutbox.Kind.AC_SCRIPT, NO_COMPANION, "sink", "sink", scriptName, scriptBody);
        return d.artifactId();
    }

    /** 投放一份携带器内容 → 进投递箱等携带器下游（⚠️ 目前尚无消费者）。 */
    @Override
    public void publishCarrier(String carrierName, String content) {
        outbox.submit(ArtifactOutbox.Kind.CARRIER, NO_COMPANION, "sink", "sink", carrierName, content);
    }

    @Override
    public boolean available() {
        return true;
    }
}