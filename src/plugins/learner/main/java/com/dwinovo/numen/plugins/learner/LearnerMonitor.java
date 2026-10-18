package com.dwinovo.numen.plugins.learner;

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
 * learner 模块的观测出口：往 {@code config/numen/monitor/learner.jsonl} 追加 JSON 行。
 *
 * <p>抄 {@code ExperienceMonitor}（plugins/experience/.../ExperienceMonitor.java）：
 * 不依赖引擎内部类，插件自己写文件，行格式与 MonitoringJournal 对齐，监测台可原样解析。
 */
final class LearnerMonitor {

    private static final Gson GSON = new Gson();

    private LearnerMonitor() {}

    static void publish(String type, Map<String, ?> data) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve("learner.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "learner-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "learner");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响 learner 主流程
        }
    }
}
