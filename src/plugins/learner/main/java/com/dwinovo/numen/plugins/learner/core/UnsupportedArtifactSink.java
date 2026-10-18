package com.dwinovo.numen.plugins.learner.core;

import java.util.Map;

/**
 * {@link ArtifactSink} 的<b>历史占位实现</b>：三个方法全部显式抛错。
 *
 * <p><b>为什么不静默 no-op</b>：一个「接了但什么都不做」的 sink 会让
 * 「学习者写不出产物」这件事<b>看起来像成功</b>，然后在第 2 批变成一个查不到根因的哑故障。
 * 显式抛错让「没做」在调用点第一眼就暴露。
 *
 * <p><b>⚠️ 2026-10-05 起已退休，生产代码不再实例化它</b>：B6/S2 把 AC/携带器真正接通了，
 * {@link LearnerPlugin} 现在装的是 {@link OutboxArtifactSink}。
 * 本类仅供「引用那句为什么当初抛错的文案」用（{@link #reason()}），
 * 以及给历史测试当反面教材 —— <b>不要</b>再拿它当产物投放口。
 *
 * <p><b>为什么不在第 1 批就实现 AC</b>：用户 2026-10-01 明确「暂时不要包含，降低复杂度」。
 * 现在接通时接口没变、只换了实现类 —— 这正是当初「只留接口」的意义。
 */
public final class UnsupportedArtifactSink implements ArtifactSink {

    private static final String MSG =
            "ArtifactSink 本批不做（not implemented in this batch）: 38号v3 B16 says "
                    + "\"学习者可以自己写 ac，但这一波不包含 ac，暂时不要包含，降低复杂度\" "
                    + "(用户 2026-10-01 原话). 这里显式抛错而不是静默 no-op，"
                    + "是为了让「调了却没实现」一眼可见，而不是变成查不到根因的哑故障。";

    @Override
    public void publishExperience(String experienceId, Map<String, Object> experience) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public String publishAcScript(String scriptName, String scriptBody) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public void publishCarrier(String carrierName, String content) {
        throw new UnsupportedOperationException(MSG);
    }

    @Override
    public boolean available() {
        return false;
    }

    /** 供工具/观测说明「为什么没实现」时引用，不要另写一份文案。 */
    public static String reason() {
        return MSG;
    }
}