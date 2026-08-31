package com.dwinovo.numen.plugins.selfcompile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Creates isolated, traceable workspaces without touching the host project. */
public final class MutationWorkspace {

    private final Path root;

    public MutationWorkspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public MutationManifest create(String requirement) throws IOException {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        String id = "mutation-" + Instant.now().toString().replace(':', '-')
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path workspace = root.resolve(id).normalize();
        if (!workspace.startsWith(root)) {
            throw new IOException("workspace escaped mutation root");
        }
        Files.createDirectories(workspace.resolve("source"));
        Files.createDirectories(workspace.resolve("classes"));
        Files.createDirectories(workspace.resolve("artifacts"));
        Files.createDirectories(workspace.resolve("reports"));

        MutationManifest manifest = new MutationManifest(id, requirement,
                MutationState.WORKSPACE_CREATED, 0, Instant.now(),
                workspace.toString(), "", "");
        writeManifest(workspace, manifest);
        return manifest;
    }

    public static void writeManifest(Path workspace, MutationManifest manifest) throws IOException {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path file = normalizedWorkspace.resolve("manifest.json").normalize();
        if (!file.startsWith(normalizedWorkspace)) {
            throw new IOException("manifest path escaped workspace");
        }
        String json = "{\n"
                + "  \"id\": \"" + quote(manifest.id()) + "\",\n"
                + "  \"requirement\": \"" + quote(manifest.requirement()) + "\",\n"
                + "  \"state\": \"" + manifest.state() + "\",\n"
                + "  \"attempt\": " + manifest.attempt() + ",\n"
                + "  \"createdAt\": \"" + manifest.createdAt() + "\",\n"
                + "  \"workspace\": \"" + quote(manifest.workspace()) + "\",\n"
                + "  \"artifact\": \"" + quote(manifest.artifact()) + "\",\n"
                + "  \"failure\": \"" + quote(manifest.failure()) + "\"\n"
                + "}\n";
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
