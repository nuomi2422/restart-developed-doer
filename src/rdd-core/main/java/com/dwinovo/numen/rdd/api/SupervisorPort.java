package com.dwinovo.numen.rdd.api;

import java.util.Map;

/** Semantic decision boundary; implementations may call NUMEN's configured AI. */
public interface SupervisorPort {
    SupervisorDecision decide(SupervisorRequest request);

    record SupervisorRequest(String purpose, String targetNodeId, String description,
                             Map<String, Object> observations) {
        public SupervisorRequest {
            require(purpose, "purpose");
            require(targetNodeId, "targetNodeId");
            require(description, "description");
            observations = observations == null ? Map.of() : Map.copyOf(observations);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
