package com.dwinovo.numen.worldstate.core.query;

import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

/**
 * 读取已有观测的结果。数据之外还要带上「有多旧 / 覆盖多全 / 能不能确认」，这样任务链
 * <b>不会把"读不到"当成"没有"</b>。
 *
 * @param present    有没有观测
 * @param observation 观测（无则 null）
 * @param key        查询键（无观测时也有，便于回执）
 * @param freshness  当前新鲜度
 * @param ageMillis  距上次采集的毫秒数
 * @param coverage   覆盖情况
 * @param reason     不能确认时的原因
 * @param note       给模型看的一句话结论
 */
public record ReadResult(boolean present, Observation observation, ObservationKey key,
                         Freshness freshness, long ageMillis, Coverage coverage,
                         FailReason reason, String note) {

    public static ReadResult missing(ObservationKey key) {
        return new ReadResult(false, null, key, Freshness.UNKNOWN, -1L, null,
                FailReason.NONE, "no observation yet; call world_state action=refresh first");
    }

    public static ReadResult present(Observation observation, Freshness freshness, long ageMillis,
                                     Coverage coverage, String note) {
        return new ReadResult(true, observation, observation.key(), freshness, ageMillis, coverage,
                FailReason.NONE, note);
    }

    /** 不能据此断言"空"（覆盖不全或观测已陈旧）。 */
    public boolean canProveAbsence() {
        return present && coverage != null && coverage.canProveAbsence()
                && freshness == Freshness.CURRENT;
    }
}
