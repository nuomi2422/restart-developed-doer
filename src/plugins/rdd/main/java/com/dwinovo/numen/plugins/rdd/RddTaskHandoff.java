package com.dwinovo.numen.plugins.rdd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Clear only this companion's handoff before publishing an in-memory removal. */
final class RddTaskHandoff {
    private RddTaskHandoff() {}

    static void remove(Path directory, UUID companionId, Runnable removeFromMemory) throws IOException {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(removeFromMemory);
        if (directory != null) {
            // Delete staging first: failure must leave the authoritative .json intact.
            // UUID-derived exact paths, no recursive deletion or directory-wide cleanup.
            Files.deleteIfExists(directory.resolve(companionId + ".json.tmp"));
            Files.deleteIfExists(directory.resolve(companionId + ".json"));
        }
        removeFromMemory.run();
    }
}
