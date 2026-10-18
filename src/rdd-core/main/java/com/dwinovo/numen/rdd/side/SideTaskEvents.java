package com.dwinovo.numen.rdd.side;

import java.util.Map;

/**
 * 支线事件出口（监测台）。只陈述事实，不以"事件发出去了"代替状态真的变了或真的恢复了主线。
 */
public interface SideTaskEvents {

    void publish(String event, Map<String, Object> data);

    SideTaskEvents NOOP = (event, data) -> {
    };
}
