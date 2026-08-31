package com.dwinovo.numen.rdd.api;

import java.util.List;

public record Goal(String id, String description, List<PrimaryGoal> primaryGoals) {
    public Goal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("goal id required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("goal description required");
        if (primaryGoals == null || primaryGoals.isEmpty()) throw new IllegalArgumentException("at least one primary goal required");
        primaryGoals = List.copyOf(primaryGoals);
    }
}
