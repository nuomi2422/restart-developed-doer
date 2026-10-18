package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Topic;

/**
 * 有类型观测载荷的根接口。每个主题一个具体 record，<b>不要让载荷退化成无约束的 Map</b>——
 * 类型明确才能写离线单测、才能安全地演进。
 */
public interface ObservationPayload {

    Topic topic();
}
