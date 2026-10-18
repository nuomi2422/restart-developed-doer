package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Persistent current-state store for reusable <b>world</b> assets, separate from task handoffs.
 *
 * <p><b>背包资产（inventory_scan）有意不落盘</b>（2026-09-25 决策）：背包是高频易变真相，
 * 昨天的钻石剑不代表今天还有；持有真相永远来自 realtime 扫描（{@code countInventory}）。
 * 落盘只保存坐标稳定的 {@code world_*}（村庄/基地/箱子/结构/机器），可跨重启复用。
 * 因此 {@link #isWorldAsset} 只放行 {@code world_} 前缀是本模块的<b>设计不变量</b>，不是遗漏。
 */
final class RddAssetStore {
    private RddAssetStore() {}

    /** 只有 world_* 需要持久化；inventory_scan 有意排除（背包不落盘）。 */
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
