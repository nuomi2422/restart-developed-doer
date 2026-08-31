package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MutationArtifactStoreTest {
    @Test
    void promotesOnlyCompiledClassWithinWorkspace() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-artifact-");
        MutationWorkspace workspace = new MutationWorkspace(root);
        MutationManifest created = workspace.create("artifact");
        Path classes = Path.of(created.workspace(), "classes");
        Path classFile = classes.resolve("Tool.class");
        Files.write(classFile, new byte[]{1, 2, 3});
        MutationManifest compiled = new MutationManifest(created.id(), created.requirement(), MutationState.COMPILED,
                0, Instant.now(), created.workspace(), "", "");

        MutationArtifactStore store = new MutationArtifactStore(root);
        Path candidate = store.promote(compiled, classFile);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(candidate));
        assertTrue(candidate.startsWith(root.resolve("candidates")));
    }

    @Test
    void refusesUncompiledMutationAndOutsideArtifact() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-artifact-");
        MutationWorkspace workspace = new MutationWorkspace(root);
        MutationManifest created = workspace.create("artifact");
        Path outside = root.resolve("outside.class");
        Files.write(outside, new byte[]{1});
        MutationArtifactStore store = new MutationArtifactStore(root);
        assertThrows(IllegalStateException.class, () -> store.promote(created, outside));
        MutationManifest compiled = new MutationManifest(created.id(), created.requirement(), MutationState.COMPILED,
                0, Instant.now(), created.workspace(), "", "");
        assertThrows(java.io.IOException.class, () -> store.promote(compiled, outside));
    }
}
