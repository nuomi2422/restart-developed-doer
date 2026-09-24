package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Persistent current-state store for reusable world assets, separate from task handoffs. */
final class RddAssetStore {
    private RddAssetStore() {}

    static boolean isWorldAsset(AssetRegistry.AssetEntry entry) {
        return entry != null && entry.observation().type().startsWith("world_");
    }

    static AssetRegistry load(Path directory, UUID companionId) {
        AssetRegistry registry = new AssetRegistry();
        if (directory == null || companionId == null) return registry;
        Path file = directory.resolve(companionId + ".json");
        if (!Files.isRegularFile(file)) return registry;
        try {
            AssetRegistry persisted = AssetRegistry.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            for (var entry : persisted.snapshot()) {
                if (isWorldAsset(entry)) registry.restore(entry);
            }
        } catch (IOException | RuntimeException ignored) {
            // A damaged optional asset cache must never block task-chain recovery.
        }
        return registry;
    }

    static void save(Path directory, UUID companionId, AssetRegistry registry) throws IOException {
        if (directory == null || companionId == null || registry == null) return;
        Files.createDirectories(directory);
        AssetRegistry persisted = new AssetRegistry();
        for (var entry : registry.snapshot()) {
            if (isWorldAsset(entry)) persisted.restore(entry);
        }
        Path target = directory.resolve(companionId + ".json");
        Path temporary = directory.resolve(companionId + ".json.tmp");
        Files.writeString(temporary, persisted.toJson(), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
