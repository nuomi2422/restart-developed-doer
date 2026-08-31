package com.dwinovo.numen.plugins.selfcompile;

/** Lifecycle of one bounded self-compile attempt. */
public enum MutationState {
    REQUESTED,
    WORKSPACE_CREATED,
    GENERATED,
    STATICALLY_CHECKED,
    COMPILED,
    CANDIDATE,
    VERIFIED,
    DELIVERED,
    FAILED,
    ARCHIVED,
    STOP_LOSS
}
