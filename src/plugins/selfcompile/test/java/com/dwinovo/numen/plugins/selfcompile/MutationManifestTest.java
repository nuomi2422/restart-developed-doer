package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutationManifestTest {

    @Test
    void transitionPreservesIdentityAndMovesState() {
        MutationManifest first = new MutationManifest("m1", "repair", MutationState.REQUESTED,
                0, Instant.parse("2026-08-30T00:00:00Z"), "w", "", "");
        MutationManifest next = first.transition(MutationState.FAILED, "compile failed");

        assertEquals("m1", next.id());
        assertEquals(first.createdAt(), next.createdAt());
        assertEquals(MutationState.FAILED, next.state());
        assertEquals("compile failed", next.failure());
    }
}
