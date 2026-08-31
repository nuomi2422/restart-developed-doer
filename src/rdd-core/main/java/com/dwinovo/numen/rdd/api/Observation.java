package com.dwinovo.numen.rdd.api;

import java.util.Map;
import java.util.Objects;

public record Observation(
        String observationId,
        String type,
        String source,
        String environmentId,
        long observedAt,
        Map<String, Object> value) {
    public Observation {
        if (observationId == null || observationId.isBlank()) throw new IllegalArgumentException("observationId required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("observation type required");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("observation source required");
        if (environmentId == null || environmentId.isBlank()) throw new IllegalArgumentException("environmentId required");
        value = value == null ? Map.of() : Map.copyOf(value);
    }
}
