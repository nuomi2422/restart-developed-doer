package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class MutationWorkspaceTest {

    @Test
    void createsAuditableWorkspaceAndManifest() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-test-");
        MutationManifest manifest = new MutationWorkspace(root).create("repair mining");
        Path workspace = Path.of(manifest.workspace());

        assertEquals(MutationState.WORKSPACE_CREATED, manifest.state());
        assertTrue(workspace.startsWith(root));
        assertTrue(Files.isDirectory(workspace.resolve("source")));
        assertTrue(Files.isDirectory(workspace.resolve("classes")));
        assertTrue(Files.isDirectory(workspace.resolve("artifacts")));
        assertTrue(Files.isDirectory(workspace.resolve("reports")));
        assertTrue(Files.readString(workspace.resolve("manifest.json")).contains("WORKSPACE_CREATED"));
    }

    @Test
    void rejectsBlankRequirement() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-test-");
        MutationWorkspace workspace = new MutationWorkspace(root);
        assertThrows(IllegalArgumentException.class, () -> workspace.create("  "));
        assertEquals(0, Files.list(root).count());
    }

    @Test
    void escapesManifestText() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-test-");
        String requirement = "quote \" and newline\nnext";
        MutationManifest manifest = new MutationWorkspace(root).create(requirement);
        String json = Files.readString(Path.of(manifest.workspace(), "manifest.json"));
        assertTrue(json.contains("quote \\\" and newline\\nnext"));
    }

    @Test
    void createsUniqueWorkspacesConcurrently() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-test-");
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<MutationManifest>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                jobs.add(() -> new MutationWorkspace(root).create("requirement"));
            }
            var manifests = pool.invokeAll(jobs).stream().map(f -> {
                try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
            assertEquals(12, manifests.stream().map(MutationManifest::id).distinct().count());
            assertEquals(12, manifests.stream().map(MutationManifest::workspace).distinct().count());
        } finally {
            pool.shutdownNow();
        }
    }
}
