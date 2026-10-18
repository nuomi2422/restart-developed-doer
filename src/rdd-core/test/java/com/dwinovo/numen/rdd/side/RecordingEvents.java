package com.dwinovo.numen.rdd.side;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 记录支线事件的测试替身。 */
final class RecordingEvents implements SideTaskEvents {

    final List<String> events = new ArrayList<>();
    final List<Map<String, Object>> payloads = new ArrayList<>();

    @Override
    public void publish(String event, Map<String, Object> data) {
        events.add(event);
        payloads.add(data);
    }

    int count(String event) {
        int n = 0;
        for (String e : events) {
            if (e.equals(event)) {
                n++;
            }
        }
        return n;
    }

    boolean saw(String event) {
        return events.contains(event);
    }
}
