package com.dwinovo.numen.plugins.rdd;

import com.google.gson.Gson;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RDD 任务链的观测出口：往 {@code config/numen/monitor/rdd.jsonl} 追加 JSON 行。
 *
 * <p>不依赖引擎内部类（MonitoringJournal 不在瘦 API jar），插件自己写文件，
 * 行格式与 MonitoringJournal 的 envelope 对齐，监测台可原样解析。
 */
public final class RddMonitor {

    private static final Gson GSON = new Gson();

    private RddMonitor() {}

    public static void publish(String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve("rdd.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "rdd-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "rdd");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响 RDD 主流程
        }
    }
}
