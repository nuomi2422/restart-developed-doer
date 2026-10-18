package com.dwinovo.numen.worldstate.core.provider;

import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;

/**
 * 采集器一次成功采集的结果：有类型的载荷 + 覆盖情况。
 *
 * <p>采集<b>失败</b>不走这里——抛 {@link ProviderException}，让上层明确报失败原因、
 * 并保留上一次观测（失败 ≠ 清空）。
 */
public record ProviderResult(ObservationPayload payload, Coverage coverage) {

    public static ProviderResult of(ObservationPayload payload, Coverage coverage) {
        return new ProviderResult(payload, coverage);
    }
}
