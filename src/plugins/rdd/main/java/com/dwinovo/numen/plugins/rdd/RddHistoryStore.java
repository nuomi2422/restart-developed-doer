package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetHistory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * P2.1 资产历史的磁盘持久化：{@code config/numen/rdd-history/<uuid>.json}。
 *
 * <p>与 {@link RddFactStore}/{@link RddAssetStore} 同构（原子 tmp+move）。历史跨重启保留：
 * "丢了的装备在哪、能不能回去拿"是长期线索，不该因重启消失。坏文件不阻断（回退空历史）。
 */
final class RddHistoryStore {
    private RddHistoryStore() {}

    static AssetHistory load(Path directory, UUID companionId) {
        if (directory == null || companionId == null) return new AssetHistory();
        Path file = directory.resolve(companionId + ".json");
        if (!Files.isRegularFile(file)) return new AssetHistory();
        try {
            return AssetHistory.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            return new AssetHistory();
        }
    }

    static void save(Path directory, UUID companionId, AssetHistory history) throws IOException {
        if (directory == null || companionId == null || history == null) return;
        Files.createDirectories(directory);
        Path target = directory.resolve(companionId + ".json");
        Path temporary = directory.resolve(companionId + ".json.tmp");
        Files.writeString(temporary, history.toJson(), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
