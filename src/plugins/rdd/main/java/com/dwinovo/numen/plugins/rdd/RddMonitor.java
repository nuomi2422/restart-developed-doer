package com.dwinovo.numen.plugins.rdd;

import com.google.gson.Gson;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RDD 任务链的观测出口：往 {@code config/numen/monitor/rdd.jsonl} 追加 JSON 行。
 *
 * <p>不依赖引擎内部类（MonitoringJournal 不在瘦 API jar），插件自己写文件，
 * 行格式与 MonitoringJournal 的 envelope 对齐，监测台可原样解析。
 *
 * <p>带轮转与归档上限：rdd.jsonl 曾因「每条 taskchain_snapshot 内嵌全量资产表 + 懒边界每秒重发」
 * 涨到 288 MB。事件侧已各自收敛；这里再兜一层——单文件超 {@link #MAX_FILE_BYTES} 即轮转，
 * 只保留最近 {@link #MAX_ROTATED_FILES} 个归档，保证观测目录总量有界，长时间实机不会撑爆磁盘。
 */
public final class RddMonitor {

    private static final Gson GSON = new Gson();
    /** 单文件上限，与 MonitoringJournal 的 16 MB 约定一致。 */
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    /** 保留的轮转归档数量上限；更旧的删除（纯观测产物，不影响任务链状态）。 */
    private static final int MAX_ROTATED_FILES = 5;
    private static final String FILE_NAME = "rdd.jsonl";
    private static final String ROTATED_PREFIX = "rdd-";

    private RddMonitor() {}

    public static synchronized void publish(String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_NAME);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "rdd-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "rdd");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            if (Files.exists(file) && Files.size(file) >= MAX_FILE_BYTES) {
                rotate(dir, file);
            }

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响 RDD 主流程
        }
    }

    /** 把写满的 rdd.jsonl 挪成带时间戳的归档，再清出目录里的超量归档。 */
    private static void rotate(Path dir, Path file) throws IOException {
        Files.move(file, dir.resolve(ROTATED_PREFIX + System.currentTimeMillis() + ".jsonl"),
                StandardCopyOption.REPLACE_EXISTING);
        prune(dir);
    }

    /** 只保留最近 MAX_ROTATED_FILES 个归档（按最后修改时间倒序）。 */
    private static void prune(Path dir) throws IOException {
        List<Path> stale;
        try (var stream = Files.list(dir)) {
            stale = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(ROTATED_PREFIX) && name.endsWith(".jsonl")
                                && !name.equals(FILE_NAME);
                    })
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .skip(MAX_ROTATED_FILES)
                    .toList();
        }
        for (Path p : stale) {
            Files.deleteIfExists(p);
        }
    }
}
