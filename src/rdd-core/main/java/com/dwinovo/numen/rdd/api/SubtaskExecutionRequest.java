package com.dwinovo.numen.rdd.api;

import java.util.Map;

/** A small, host-independent request envelope for RDD execution adapters. */
public record SubtaskExecutionRequest(String executionId, String taskNodeId, String environmentId,
                                      String description, Map<String, Object> parameters) {
    public SubtaskExecutionRequest {
        require(executionId, "executionId");
        require(taskNodeId, "taskNodeId");
        require(environmentId, "environmentId");
        require(description, "description");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
