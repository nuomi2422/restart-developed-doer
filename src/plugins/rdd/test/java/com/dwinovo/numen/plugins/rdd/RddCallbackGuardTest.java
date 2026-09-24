package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RddCallbackGuardTest {
    private final RddCallbackGuard guard = new RddCallbackGuard();
    private final UUID companion = UUID.randomUUID();
    private final ArrayDeque<Runnable> serverQueue = new ArrayDeque<>();
    private final Executor server = serverQueue::addLast;

    private TaskChain unexpanded(String description) {
        // Repeated objectives can have identical persisted IDs; identity must still differ.
        return new TaskChain(new Goal("same-goal-id", description,
                List.of(PrimaryGoal.unexpanded("same-primary-id", description, List.of()))));
    }

    @Test void queuedStageAResultCannotResurrectClearedGoal() {
        var a = guard.replace(companion);
        AtomicReference<TaskChain> live = new AtomicReference<>();
        guard.dispatch(a, server, () -> live.set(unexpanded("A")));
        guard.replace(companion); // clear invalidates even a completion already in the server queue
        serverQueue.removeFirst().run();
        assertNull(live.get());
    }

    @Test void resubmittingIdenticalObjectiveRejectsLateSuccessAndFailure() {
        var a = guard.replace(companion);
        var b = guard.replace(companion);
        AtomicReference<TaskChain> live = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        TaskChain newest = unexpanded("same objective");
        guard.dispatch(b, server, () -> live.set(newest));
        guard.dispatch(a, server, () -> live.set(unexpanded("same objective")));
        guard.dispatch(a, server, failures::incrementAndGet);
        while (!serverQueue.isEmpty()) serverQueue.removeFirst().run();
        assertSame(newest, live.get());
        assertEquals(0, failures.get());
    }

    @Test void fallbackKeepsGenerationButOldSinglePassCannotOverwriteReplacement() {
        var a = guard.replace(companion);
        AtomicReference<TaskChain> live = new AtomicReference<>();
        // Stage-A fails; its single-pass callback is scheduled under the SAME ticket.
        guard.dispatch(a, server, () -> guard.dispatch(a, server,
                () -> live.set(unexpanded("old fallback"))));
        serverQueue.removeFirst().run();
        var b = guard.replace(companion);
        TaskChain newest = unexpanded("B");
        guard.dispatch(b, server, () -> live.set(newest));
        while (!serverQueue.isEmpty()) serverQueue.removeFirst().run();
        assertSame(newest, live.get());
    }

    @Test void currentFallbackCanCompleteWithoutASecondGoalSubmission() {
        var a = guard.replace(companion);
        AtomicReference<TaskChain> live = new AtomicReference<>();
        guard.dispatch(a, server, () -> guard.dispatch(a, server,
                () -> live.set(unexpanded("fallback"))));
        while (!serverQueue.isEmpty()) serverQueue.removeFirst().run();
        assertEquals("fallback", live.get().goal().description());
        assertTrue(guard.isCurrent(a));
    }

    @Test void oldStageBDoesNotExpandReplacementOrSpendItsBudget() {
        var a = guard.replace(companion);
        var b = guard.replace(companion);
        TaskChain replacement = unexpanded("B");
        AtomicInteger attempts = new AtomicInteger();
        guard.dispatch(a, server, () -> replacement.expandCurrentPrimary(List.of(
                Subtask.hardCoded("old-s0", "old step", Map.of("asset_key", "wrong", "minimum", 1)))));
        guard.dispatch(a, server, attempts::incrementAndGet);
        while (!serverQueue.isEmpty()) serverQueue.removeFirst().run();
        assertTrue(replacement.currentPrimary().unexpanded());
        assertEquals(0, attempts.get());
        guard.dispatch(b, server, () -> replacement.expandCurrentPrimary(List.of(
                Subtask.hardCoded("new-s0", "correct step", Map.of("asset_key", "right", "minimum", 1)))));
        serverQueue.removeFirst().run();
        assertEquals("correct step", replacement.currentSubtask().description());
    }

    @Test void callbackWaitsForServerExecutorAndWorldStopInvalidatesIt() {
        var a = guard.current(companion);
        AtomicInteger applied = new AtomicInteger();
        guard.dispatch(a, server, applied::incrementAndGet);
        assertEquals(0, applied.get());
        guard.clear();
        guard.current(companion); // same companion after world restart
        serverQueue.removeFirst().run();
        assertEquals(0, applied.get());
    }

    @Test void replacingAnotherCompanionDoesNotCancelThisGoal() {
        var a = guard.replace(companion);
        AtomicInteger applied = new AtomicInteger();
        guard.replace(UUID.randomUUID());
        guard.dispatch(a, server, applied::incrementAndGet);
        serverQueue.removeFirst().run();
        assertEquals(1, applied.get());
    }

    @Test void worldStopClearsInFlightOwnerStateAndFreshWorldCanPlan() {
        var old = guard.replace(companion);
        var decomposing = new java.util.HashSet<UUID>();
        var inFlight = new java.util.HashMap<UUID, Object>();
        decomposing.add(companion);
        inFlight.put(companion, new Object());
        AtomicReference<TaskChain> live = new AtomicReference<>();
        guard.dispatch(old, server, () -> live.set(unexpanded("previous world")));
        guard.clear(() -> {
            decomposing.clear();
            inFlight.clear();
            live.set(null);
        });
        assertFalse(decomposing.contains(companion));
        assertFalse(inFlight.containsKey(companion));
        var fresh = guard.replace(companion);
        TaskChain nextWorld = unexpanded("new world");
        guard.dispatch(fresh, server, () -> live.set(nextWorld));
        while (!serverQueue.isEmpty()) serverQueue.removeFirst().run();
        assertSame(nextWorld, live.get());
    }
}
