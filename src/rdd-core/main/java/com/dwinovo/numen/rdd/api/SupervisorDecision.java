package com.dwinovo.numen.rdd.api;

public record SupervisorDecision(SupervisorDecisionType type, String targetNodeId, String reason) {
    public SupervisorDecision {
        if (type == null) throw new IllegalArgumentException("decision type required");
        if (targetNodeId == null || targetNodeId.isBlank()) throw new IllegalArgumentException("target node id required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("decision reason required");
    }
}
