package com.dwinovo.numen.rdd.api;

import java.util.List;

/**
 * Stage-A 规划器产出的单个一级(阶段)规格：主题描述 + 可选的跨级前置资产门(waitFor)。
 *
 * <p>一级是阶段语义（主题 + 可选依赖 + 二级懒加载集合），不是资产任务：真正的
 * asset_key + minimum + 硬检测只出现在二级。waitFor 为空 = 该阶段无跨级资产门，
 * 按链序直接进入（线性推进依然成立）。
 */
public record PrimarySpec(String description, List<AssetRequirement> waitFor) {
    public PrimarySpec {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("stage description required");
        }
        waitFor = waitFor == null || waitFor.isEmpty() ? List.of() : List.copyOf(waitFor);
    }
}
