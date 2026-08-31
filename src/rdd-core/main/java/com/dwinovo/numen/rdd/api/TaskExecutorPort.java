package com.dwinovo.numen.rdd.api;

import java.util.Map;

/** Host boundary for starting one RDD subtask; implementations may bind it to NUMEN. */
public interface TaskExecutorPort {
    ExecutionHandle start(SubtaskRequest request);
    void stop(String executionId);
    ExecutionHandle status(String executionId);

    record SubtaskRequest(String executionId, String taskNodeId, String environmentId,
                          String description, Map<String, Object> parameters) {
        public SubtaskRequest {
            require(executionId, "executionId");
            require(taskNodeId, "taskNodeId");
            require(environmentId, "environmentId");
            require(description, "description");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    record ExecutionHandle(String executionId, String taskNodeId, String environmentId,
                           ExecutionStatus status, String message) {
        public ExecutionHandle {
            require(executionId, "executionId");
            require(taskNodeId, "taskNodeId");
            require(environmentId, "environmentId");
            if (status == null) throw new IllegalArgumentException("execution status required");
        }
    }

    enum ExecutionStatus { ACCEPTED, RUNNING, PAUSED, SUCCESS, FAILED, UNAVAILABLE }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
    }
}
