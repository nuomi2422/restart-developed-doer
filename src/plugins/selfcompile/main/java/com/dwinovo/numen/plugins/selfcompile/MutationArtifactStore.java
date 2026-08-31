package com.dwinovo.numen.plugins.selfcompile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Promotes only a compiled, verified workspace artifact to an immutable candidate. */
public final class MutationArtifactStore {
    private final Path root;

    public MutationArtifactStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path promote(MutationManifest manifest, Path artifact) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(artifact, "artifact");
        if (manifest.state() != MutationState.COMPILED) {
            throw new IllegalStateException("only COMPILED mutations may become candidates");
        }
        Path workspace = Path.of(manifest.workspace()).toAbsolutePath().normalize();
        Path source = artifact.toAbsolutePath().normalize();
        if (!source.startsWith(workspace.resolve("classes")) || !Files.isRegularFile(source)) {
            throw new IOException("artifact must be a regular file under workspace/classes");
        }
        Path candidate = root.resolve("candidates").resolve(manifest.id()).resolve(source.getFileName()).normalize();
        if (!candidate.startsWith(root)) throw new IOException("candidate path escaped artifact root");
        Files.createDirectories(candidate.getParent());
        Path temp = Files.createTempFile(candidate.getParent(), ".candidate-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            return Files.move(temp, candidate, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void writeCandidateManifest(Path candidate, MutationManifest manifest) throws IOException {
        Path file = candidate.getParent().resolve("manifest.json").normalize();
        if (!file.startsWith(root)) throw new IOException("manifest path escaped artifact root");
        Files.writeString(file, "{\n  \"id\": \"" + quote(manifest.id())
                + "\",\n  \"state\": \"CANDIDATE\"\n}\n", StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
