package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.fact.CompletedFactStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 完成事实的磁盘持久化（P0）：{@code config/numen/rdd-facts/<uuid>.json}。
 *
 * <p>与 {@link RddAssetStore} 同构（原子 tmp+move），与任务交接、世界资产分开存放：
 * 完成阶段事实跨 /goal 重绑、跨重启保留，且清任务不应抹掉它。
 * 坏文件不阻断任务恢复（返回空仓库，由调用方按需告警）。
 */
final class RddFactStore {
    private RddFactStore() {}

    static CompletedFactStore load(Path directory, UUID companionId) {
        if (directory == null || companionId == null) {
            return new CompletedFactStore();
        }
        Path file = directory.resolve(companionId + ".json");
        if (!Files.isRegularFile(file)) {
            return new CompletedFactStore();
        }
        try {
            return CompletedFactStore.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            return new CompletedFactStore();
        }
    }

    static void save(Path directory, UUID companionId, CompletedFactStore store) throws IOException {
        if (directory == null || companionId == null || store == null) {
            return;
        }
        Files.createDirectories(directory);
        Path target = directory.resolve(companionId + ".json");
        Path temporary = directory.resolve(companionId + ".json.tmp");
        Files.writeString(temporary, store.toJson(), StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
