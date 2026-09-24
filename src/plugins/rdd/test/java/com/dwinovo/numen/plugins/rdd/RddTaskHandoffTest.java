package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RddTaskHandoffTest {
    @TempDir Path directory;
    private final UUID companion = UUID.randomUUID();

    private String savedChain() {
        return new TaskChain(new Goal("g", "goal",
                List.of(PrimaryGoal.unexpanded("p", "phase", List.of())))).toJson();
    }

    @Test void clearRemovesRestorableChainAndStagingBeforeMemory() throws IOException {
        Path saved = directory.resolve(companion + ".json");
        Path staging = directory.resolve(companion + ".json.tmp");
        Files.writeString(saved, savedChain());
        Files.writeString(staging, savedChain());
        AtomicBoolean removed = new AtomicBoolean();
        RddTaskHandoff.remove(directory, companion, () -> {
            assertFalse(Files.exists(saved));
            assertFalse(Files.exists(staging));
            removed.set(true);
        });
        assertTrue(removed.get());
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(f -> f.getFileName().toString().endsWith(".json")).count());
        }
    }

    @Test void clearIsIdempotentAndPreservesOtherCompanionsAndUnrelatedFiles() throws IOException {
        UUID other = UUID.randomUUID();
        Path otherSaved = directory.resolve(other + ".json");
        Files.writeString(otherSaved, savedChain());
        Files.writeString(directory.resolve("notes.txt"), "keep");
        RddTaskHandoff.remove(directory, companion, () -> {});
        RddTaskHandoff.remove(directory, companion, () -> {});
        assertEquals("goal", TaskChain.fromJson(Files.readString(otherSaved)).goal().description());
        assertEquals("keep", Files.readString(directory.resolve("notes.txt")));
    }

    @Test void stagingDeletionFailureRetainsAuthoritativeChainAndMemory() throws IOException {
        Path saved = directory.resolve(companion + ".json");
        Files.writeString(saved, savedChain());
        Path blocked = Files.createDirectory(directory.resolve(companion + ".json.tmp"));
        Files.writeString(blocked.resolve("do-not-delete"), "keep");
        AtomicBoolean removed = new AtomicBoolean();
        assertThrows(IOException.class, () -> RddTaskHandoff.remove(directory, companion, () -> removed.set(true)));
        assertFalse(removed.get());
        assertEquals("goal", TaskChain.fromJson(Files.readString(saved)).goal().description());
        assertEquals("keep", Files.readString(blocked.resolve("do-not-delete")));
    }

    @Test void authoritativeDeletionFailureDoesNotReportMemoryRemoval() throws IOException {
        Path blocked = Files.createDirectory(directory.resolve(companion + ".json"));
        Files.writeString(blocked.resolve("do-not-delete"), "keep");
        AtomicBoolean removed = new AtomicBoolean();
        assertThrows(IOException.class, () -> RddTaskHandoff.remove(directory, companion, () -> removed.set(true)));
        assertFalse(removed.get());
        assertTrue(Files.exists(blocked.resolve("do-not-delete")));
    }

    @Test void clearBeforePersistenceSetupStillRemovesMemory() throws IOException {
        AtomicBoolean removed = new AtomicBoolean();
        RddTaskHandoff.remove(null, companion, () -> removed.set(true));
        assertTrue(removed.get());
    }
}
