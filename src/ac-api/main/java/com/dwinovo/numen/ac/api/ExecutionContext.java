package com.dwinovo.numen.ac.api;

import java.util.Map;

public interface ExecutionContext {
    Map<String, Object> attributes();
    default Object attribute(String key) { return attributes().get(key); }
}
