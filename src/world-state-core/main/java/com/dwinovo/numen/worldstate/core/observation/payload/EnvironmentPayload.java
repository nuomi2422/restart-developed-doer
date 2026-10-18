package com.dwinovo.numen.worldstate.core.observation.payload;

import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;

/**
 * 环境快照：维度、游戏时间、天气。范围无关，采集廉价，是第一版两个真实采集器之一。
 *
 * @param dimension  维度 id
 * @param dayTime    该维度游戏时间（tick）
 * @param raining    是否下雨
 * @param thundering 是否雷暴
 */
public record EnvironmentPayload(String dimension, long dayTime, boolean raining, boolean thundering)
        implements ObservationPayload {

    @Override
    public Topic topic() {
        return Topic.ENVIRONMENT;
    }

    /** 归一化天气词（给模型直接可读的结论，不让它自己去拼 raining/thundering）。 */
    public String weather() {
        if (thundering) return "thunder";
        if (raining) return "rain";
        return "clear";
    }
}
