package com.dwinovo.numen.plugins.settlement;

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
 * 基地与设施的<b>观测出口</b>（用户 2026-10-10 点名「接到监测台」）。
 *
 * <p>把 settlement 的语义事件追加到 {@code config/numen/monitor/settlement.jsonl}（category =
 * {@code settlement}），监测台 8776 的「2.4 基地与设施」页只读展示。envelope 与监测台对齐
 * （schema_version / event_id / timestamp / source / category / type / data），<b>写失败静默</b>——
 * 观测不是执行依赖。与 {@code AcxMonitor} 同口径（插件直接写文件，不依赖 api/common 的
 * {@code MonitoringJournal}——那个包不在插件编译类路径上）。
 *
 * <p>为什么单开一条流：{@code tools.jsonl} 里已有 settlement 的 tool_call/tool_result（通用工具面），
 * 但没有「哪个模板 / 哪一格 / 底座基准点 / 受理还是被拒 / 还差几格」这类<b>语义</b>。
 */
public final class SettlementMonitor {

    private static final Gson GSON = new Gson();

    private SettlementMonitor() {}

    public static void publish(String type, Map<String, ?> data) {
        if (type == null) return;
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("config").resolve("numen").resolve("monitor");
            Files.createDirectories(dir);
            Path file = dir.resolve("settlement.jsonl");

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema_version", 1);
            envelope.put("event_id", "settlement-" + System.nanoTime());
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("source", "numen");
            envelope.put("category", "settlement");
            envelope.put("type", type);
            envelope.put("data", data == null ? Map.of() : data);

            Files.writeString(file, GSON.toJson(envelope) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 观测失败不影响基地主流程
        }
    }
}
