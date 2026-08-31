package com.dwinovo.numen.plugins.selfcompile;

import java.time.Instant;
import java.util.Objects;

/** Immutable record of a self-compile attempt. */
public record MutationManifest(
        String id,
        String requirement,
        MutationState state,
        int attempt,
        Instant createdAt,
        String workspace,
        String artifact,
        String failure) {

    public MutationManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public MutationManifest transition(MutationState next, String nextFailure) {
        return new MutationManifest(id, requirement, next, attempt, createdAt,
                workspace, artifact, nextFailure);
    }
}
