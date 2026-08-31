package com.dwinovo.numen.plugins.selfcompile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes generated source only after the deterministic static gate passes. */
public final class MutationSourceStore {
    private final MutationWorkspace workspace;

    public MutationSourceStore(MutationWorkspace workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public Path write(MutationManifest manifest, String fileName, String source) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        if (fileName == null || !fileName.matches("[A-Za-z][A-Za-z0-9_]{0,63}\\.java")) {
            throw new IllegalArgumentException("fileName must be a simple Java filename");
        }
        MutationStaticChecker.CheckResult check = MutationStaticChecker.check(source);
        if (!check.accepted()) {
            throw new IllegalArgumentException("source rejected: " + String.join(", ", check.violations()));
        }
        Path dir = Path.of(manifest.workspace()).toAbsolutePath().normalize().resolve("source").normalize();
        if (!dir.startsWith(workspace.root())) throw new IOException("source path escaped mutation root");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) throw new IOException("source path escaped workspace");
        Path temp = Files.createTempFile(dir, ".source-", ".tmp");
        try {
            Files.writeString(temp, source, StandardCharsets.UTF_8);
            return Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
