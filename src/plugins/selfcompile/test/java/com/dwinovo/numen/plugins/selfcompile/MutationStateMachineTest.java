package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MutationStateMachineTest {

    @Test
    void acceptsOnlyForwardGatedTransitions() {
        assertTrue(MutationStateMachine.canTransition(MutationState.REQUESTED, MutationState.WORKSPACE_CREATED));
        assertTrue(MutationStateMachine.canTransition(MutationState.CANDIDATE, MutationState.VERIFIED));
        assertTrue(MutationStateMachine.canTransition(MutationState.VERIFIED, MutationState.DELIVERED));
        assertFalse(MutationStateMachine.canTransition(MutationState.REQUESTED, MutationState.DELIVERED));
        assertFalse(MutationStateMachine.canTransition(MutationState.DELIVERED, MutationState.REQUESTED));
    }

    @Test
    void rejectsSkippingVerification() {
        MutationManifest manifest = new MutationManifest("m", "repair", MutationState.CANDIDATE,
                0, Instant.now(), "workspace", "artifact", "");
        assertThrows(IllegalStateException.class,
                () -> MutationStateMachine.transition(manifest, MutationState.DELIVERED, ""));
    }

    @Test
    void stopLossIsTerminal() {
        assertFalse(MutationStateMachine.canTransition(MutationState.STOP_LOSS, MutationState.REQUESTED));
        assertFalse(MutationStateMachine.canTransition(MutationState.STOP_LOSS, MutationState.ARCHIVED));
    }
}
