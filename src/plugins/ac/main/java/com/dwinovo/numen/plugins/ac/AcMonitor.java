package com.dwinovo.numen.plugins.ac;

import com.dwinovo.numen.ac.api.AcEvent;
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
 * AC 执行系统的观测出口：往 {@code config/numen/monitor/ac.jsonl} 追加 JSON 行。
 *
 * <p>不依赖引擎内部类（MonitoringJournal 不在瘦 API jar），插件自己写文件，
 * 行格式与 MonitoringJournal 的 envelope 对齐，监测台可原样解析。
 */
public final class AcMonitor {

    private static final Gson GSON = new Gson();

    private AcMonitor() {}

    /** 把一条 AcEvent 写成观测行。 */
    public static void publish(AcEvent e) {
        if (e == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", e.kind().name());
        data.put("execution_id", e.executionId());
        data.put("attempt", e.attempt());
        data.put("run_id", e.runId());
        data.put("ac", e.acName());
        data.put("ac_version", e.acVersion());
        if (e.stepId() != null) data.put("step_id", e.stepId());
        if (e.stepIndex() >= 0) data.put("step_index", e.stepIndex());
        if (e.tool() != null) data.put("tool", e.tool());
        if (e.message() != null && !e.message().isBlank()) data.put("message", e.message());
        publish("ac_event", data);
    }

    public static void publish(String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve("ac.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "ac-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "ac");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响 AC 主流程
        }
    }
}
