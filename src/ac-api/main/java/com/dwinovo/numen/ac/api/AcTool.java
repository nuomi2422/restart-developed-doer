package com.dwinovo.numen.ac.api;

import java.util.Map;

@FunctionalInterface
public interface AcTool {
    StepResult execute(Map<String, Object> parameters, ExecutionContext context);
}
