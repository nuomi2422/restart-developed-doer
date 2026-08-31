package com.dwinovo.numen.ac.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host-independent AC document; AI may create or replace instances as data. */
public record AcDefinition(String name, String version, List<AcStep> steps) {
    public AcDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("AC name required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("AC version required");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("AC steps required");
        steps = List.copyOf(steps);
        var ids = new java.util.HashSet<String>();
        for (AcStep step : steps) if (!ids.add(step.id())) throw new IllegalArgumentException("duplicate step id: " + step.id());
    }
    /** Compatibility constructor for programmatic AC authors. */
    public AcDefinition(String name, List<AcStep> steps) { this(name, "1", steps); }
    public record AcStep(String id, String tool, Map<String,Object> parameters) {
        public AcStep {
            if (id == null || id.isBlank() || tool == null || tool.isBlank()) throw new IllegalArgumentException("step id/tool required");
            parameters = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
        }
    }
}
