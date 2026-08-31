package com.dwinovo.numen.ac.api;

import java.util.Map;

public record StepResult(Status status, Map<String, Object> output, String message) {
    public enum Status { SUCCESS, PAUSED, FAILED }
    public static StepResult success(Map<String,Object> output) { return new StepResult(Status.SUCCESS, output, null); }
    public static StepResult paused(String message, Map<String,Object> output) { return new StepResult(Status.PAUSED, output, message); }
    public static StepResult failed(String message) { return new StepResult(Status.FAILED, Map.of(), message); }
}
