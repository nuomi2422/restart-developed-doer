package com.dwinovo.numen.plugins.selfcompile;

/** Lifecycle of one bounded self-compile attempt. */
public enum MutationState {
    /** P5 提案：已登记需求、建好隔离工作区，等待审批（不占执行预算、不生成代码）。 */
    PROPOSED,
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
