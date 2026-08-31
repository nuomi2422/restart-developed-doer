package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MutationSourceStoreTest {
    private static final String SAFE = "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class Tool {}\n";

    @Test
    void writesSafeSourceAtomicallyInsideWorkspace() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-source-");
        MutationWorkspace workspaces = new MutationWorkspace(root);
        MutationManifest manifest = workspaces.create("add tool");
        Path path = new MutationSourceStore(workspaces).write(manifest, "Tool.java", SAFE);
        assertEquals(SAFE, Files.readString(path));
        assertTrue(path.startsWith(Path.of(manifest.workspace()).resolve("source")));
    }

    @Test
    void rejectsUnsafeSourceBeforeWriting() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-source-");
        MutationWorkspace workspaces = new MutationWorkspace(root);
        MutationManifest manifest = workspaces.create("add tool");
        assertThrows(IllegalArgumentException.class,
                () -> new MutationSourceStore(workspaces).write(manifest, "Tool.java",
                        "package com.dwinovo.numen.plugins.selfcompile.generated;\nSystem.exit(1);"));
        assertEquals(0, Files.list(Path.of(manifest.workspace(), "source")).count());
    }

    @Test
    void rejectsPathTraversalFilename() throws Exception {
        Path root = Files.createTempDirectory("selfcompile-source-");
        MutationWorkspace workspaces = new MutationWorkspace(root);
        MutationManifest manifest = workspaces.create("add tool");
        assertThrows(IllegalArgumentException.class,
                () -> new MutationSourceStore(workspaces).write(manifest, "../Tool.java", SAFE));
    }
}
