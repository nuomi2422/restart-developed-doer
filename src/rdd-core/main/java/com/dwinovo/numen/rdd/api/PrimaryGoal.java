package com.dwinovo.numen.rdd.api;

import java.util.List;

public record PrimaryGoal(String id, String description, List<Subtask> subtasks) {
    public PrimaryGoal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("primary goal id required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("primary goal description required");
        if (subtasks == null || subtasks.isEmpty()) throw new IllegalArgumentException("at least one subtask required");
        subtasks = List.copyOf(subtasks);
    }
}
