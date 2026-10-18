package com.dwinovo.numen.worldstate.core.freshness;

import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.observation.Observation;

/** 把一条观测 + 当前时间 分类成新鲜度。 */
public interface FreshnessPolicy {

    Freshness classify(Observation observation, long nowMs);
}
