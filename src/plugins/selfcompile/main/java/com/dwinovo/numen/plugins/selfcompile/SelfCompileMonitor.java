package com.dwinovo.numen.plugins.selfcompile;

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
 * Self-Compile 插件自身的观测出口：往 {@code config/numen/monitor/ai.jsonl} 追加 JSON 行。
 *
 * <p>不依赖引擎内部类（MonitoringJournal 在瘦 API jar 之外），插件自己写文件。
 * 行格式与 MonitoringJournal 的 envelope 对齐，监测台可原样解析。
 */
public final class SelfCompileMonitor {

    private static final Gson GSON = new Gson();

    private SelfCompileMonitor() {}

    public static void publish(String type, Map<String, ?> data) {
        try {
            Path configDir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(configDir);
            Path file = configDir.resolve("ai.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "selfcompile-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "ai");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            String line = GSON.toJson(envelope);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响自变异主流程
        }
    }
}
