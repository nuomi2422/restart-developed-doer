package com.dwinovo.numen.plugins.worldstate;

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
 * 世界观测的<b>观测出口</b>：把 read/refresh 的语义事件追加到
 * {@code config/numen/monitor/worldstate.jsonl}（category = {@code worldstate}）。
 *
 * <p>监测台据此能看到「谁查了什么、刷新成功还是失败、失败原因、覆盖多全」。
 * envelope 与监测台对齐（与 {@code SettlementMonitor} / {@code AcxMonitor} 同口径）。
 * <b>写失败静默</b>——观测不是执行依赖，不能因为监测台写不进去而拖垮采集。
 */
public final class WorldStateMonitor {

    private static final Gson GSON = new Gson();

    private WorldStateMonitor() {
    }

    public static void publish(String type, Map<String, ?> data) {
        if (type == null) return;
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve("worldstate.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "worldstate-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "worldstate");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响世界观测主流程。
        }
    }
}
