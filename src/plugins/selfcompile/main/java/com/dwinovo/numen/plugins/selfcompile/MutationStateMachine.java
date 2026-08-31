package com.dwinovo.numen.plugins.selfcompile;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Validates the allowed forward lifecycle of one mutation attempt. */
public final class MutationStateMachine {

    private static final Map<MutationState, EnumSet<MutationState>> NEXT = new EnumMap<>(MutationState.class);

    static {
        NEXT.put(MutationState.REQUESTED, EnumSet.of(MutationState.WORKSPACE_CREATED, MutationState.FAILED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.WORKSPACE_CREATED, EnumSet.of(MutationState.GENERATED, MutationState.FAILED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.GENERATED, EnumSet.of(MutationState.STATICALLY_CHECKED, MutationState.FAILED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.STATICALLY_CHECKED, EnumSet.of(MutationState.COMPILED, MutationState.FAILED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.COMPILED, EnumSet.of(MutationState.CANDIDATE, MutationState.FAILED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.CANDIDATE, EnumSet.of(MutationState.VERIFIED, MutationState.FAILED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.VERIFIED, EnumSet.of(MutationState.DELIVERED));
        NEXT.put(MutationState.FAILED, EnumSet.of(MutationState.ARCHIVED, MutationState.STOP_LOSS));
        NEXT.put(MutationState.ARCHIVED, EnumSet.of(MutationState.REQUESTED));
        NEXT.put(MutationState.STOP_LOSS, EnumSet.noneOf(MutationState.class));
        NEXT.put(MutationState.DELIVERED, EnumSet.noneOf(MutationState.class));
    }

    private MutationStateMachine() {}

    public static boolean canTransition(MutationState from, MutationState to) {
        return from != null && to != null && NEXT.getOrDefault(from, EnumSet.noneOf(MutationState.class)).contains(to);
    }

    public static MutationManifest transition(MutationManifest manifest, MutationState next, String failure) {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        if (!canTransition(manifest.state(), next)) {
            throw new IllegalStateException("illegal mutation transition: " + manifest.state() + " -> " + next);
        }
        return manifest.transition(next, failure);
    }
}
