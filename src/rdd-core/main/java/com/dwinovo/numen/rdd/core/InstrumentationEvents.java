package com.dwinovo.numen.rdd.core;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 埋点统一事件通道的<b>纯 JVM 部分</b>：信封的拼装与解析（可单测）。
 *
 * <p>落地规矩（埋点工单 · 2026-09-25）：
 * <ul>
 *   <li><b>只做观测，不做决策</b>：产出是落盘事件 + 数据元字段，不改任何 Agent 行为逻辑。</li>
 *   <li><b>单一 JSONL、append-only</b>：每行一个事件，带 {@code schema_version} 与{@code game_time}（游戏 tick）。</li>
 *   <li><b>不做指标/聚合/可视化</b>——那些是离线分析的事；这里只管记录。</li>
 *   <li>信封字段与 {@code MonitoringJournal}/{@code rdd.jsonl} 对齐（event_id/timestamp/source/category/type/data），
 *       {@code data} 内承载事件专属字段（关联任务、上下文快照等）。</li>
 * </ul>
 *
 * <p>文件写入与限流在插件侧（{@code RddInstrumentation}）；本类只负责「一行事件」的生成与校验，
 * 保证「事件文件整体可 parse、零坏行」的验收有单测可验。
 */
public final class InstrumentationEvents {

    public static final int SCHEMA_VERSION = 1;
    /** 事件类型全集（与插件侧 RddInstrumentation 常量一一对应）。 */
    public static final String DEATH = "death";
    public static final String STARVATION_DEATH = "starvation_death";
    public static final String LOOP_DETECTED = "loop_detected";
    public static final String REPEAT_GATHER = "repeat_gather";
    public static final String RESOURCE_WASTE = "resource_waste";
    public static final String ASSET_MISMATCH = "asset_mismatch";
    public static final String RECOVERY_FAILED = "recovery_failed";
    public static final String INSTRUMENTATION_CHANGE = "instrumentation_change";

    private static final Gson GSON = new Gson();
    private static final List<String> REQUIRED = List.of("schema_version", "event_id", "type", "data");

    private InstrumentationEvents() {}

    /** 拼一行事件（envelope 形状与 MonitoringJournal 对齐）。 */
    public static String line(String eventId, String type, long gameTimeTicks, Map<String, Object> data) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("event_id", eventId);
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("source", "numen");
        envelope.put("category", "rdd_instrumentation");
        envelope.put("type", type);
        envelope.put("game_time", gameTimeTicks);
        envelope.put("data", data == null ? Map.of() : data);
        return GSON.toJson(envelope);
    }

    /**
     * 解析一行事件；格式坏/缺必备字段抛 {@link IllegalArgumentException}，绝不静默吞掉——分析端
     * 拿到的每一行都必须可 parse（验收：零坏行）。
     */
    public static Parsed parse(String line) {
        if (line == null || line.isBlank()) throw new IllegalArgumentException("empty instrumentation line");
        Map<?, ?> env;
        try {
            env = GSON.fromJson(line, Map.class);
        } catch (JsonSyntaxException ex) {
            throw new IllegalArgumentException("malformed instrumentation line", ex);
        }
        if (env == null) throw new IllegalArgumentException("empty instrumentation json");
        for (String key : REQUIRED) {
            if (!env.containsKey(key)) throw new IllegalArgumentException("missing field: " + key);
        }
        Number schema = env.get("schema_version") instanceof Number n ? n : null;
        if (schema == null || schema.intValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema_version: " + env.get("schema_version"));
        }
        Object eventId = env.get("event_id");
        Object type = env.get("type");
        if (!(eventId instanceof String) || ((String) eventId).isBlank()) {
            throw new IllegalArgumentException("invalid event_id");
        }
        if (!(type instanceof String) || ((String) type).isBlank()) {
            throw new IllegalArgumentException("invalid type");
        }
        long gameTime = env.get("game_time") instanceof Number g ? g.longValue() : -1L;
        Object data = env.get("data");
        Map<String, Object> dataMap = data instanceof Map<?, ?> m ? toObjectMap(m) : Map.of();
        return new Parsed(SCHEMA_VERSION, (String) eventId, (String) type, gameTime, Map.copyOf(dataMap));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toObjectMap(Map<?, ?> m) {
        // Gson 解析 Map 默认 LinkedHashMap<String,Object>；强转仅在运行时校验，异常交给上层。
        return (Map<String, Object>) m;
    }

    public record Parsed(int schemaVersion, String eventId, String type, long gameTime, Map<String, Object> data) {}
}