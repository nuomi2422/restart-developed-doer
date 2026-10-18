package com.dwinovo.numen.plugins.acx;

import com.dwinovo.numen.acx.api.AcxEvent;
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
 * ACX 执行系统的观测出口：往 {@code config/numen/monitor/acx.jsonl} 追加 JSON 行。
 *
 * <p>envelope 与监测台对齐（schema_version / event_id / timestamp / source / category /
 * type / data），写失败静默 —— 观测不是执行依赖。</p>
 */
public final class AcxMonitor {

    private static final Gson GSON = new Gson();

    private AcxMonitor() {}

    /** 把一条执行事件写成观测行。 */
    public static void publish(AcxEvent e) {
        if (e == null) return;
        publish("acx_event", e.toMap());
    }

    public static void publish(String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve("acx.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "acx-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "acx");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响 ACX 主流程
        }
    }
}
