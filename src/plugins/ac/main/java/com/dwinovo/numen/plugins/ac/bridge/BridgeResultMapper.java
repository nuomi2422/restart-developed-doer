package com.dwinovo.numen.plugins.ac.bridge;

import com.dwinovo.numen.ac.api.StepResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Numen 工具结果 JSON → AC {@link StepResult} 的映射（纯 JVM，可测）。
 *
 * <h3>关键语义（水土不服的补丁）</h3>
 * <ul>
 *   <li>{@code timed_out} / {@code interrupted} → AC {@code PAUSED}（未终态，可续）</li>
 *   <li>{@code success=true} 且 {@code data.async=true} → 只是<b>受理回执</b>，不是步骤完成
 *       → AC {@code PAUSED}（等 task_finished / task_status 轮询再 resume）</li>
 *   <li>其余 {@code success=true} → {@code SUCCESS}；否则 {@code FAILED}</li>
 * </ul>
 * 工具声称成功 ≠ 世界已验证：AC 只记录工具事实，观察证据由任务链/监测台旁路提供。
 */
public final class BridgeResultMapper {

    private BridgeResultMapper() {}

    public static StepResult map(String tool, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return StepResult.failed(tool + " 返回空结果");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(resultJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return StepResult.failed(tool + " 返回非 JSON 结果: " + e.getMessage());
        }

        // 裸结构化 JSON（无 success 字段）= 非身体工具直接 complete 的自定义结果
        // （如 selfcompile_status 返回 {"module":...,"state":...}）。视为工具成功
        // 产出的数据，整个对象作为 output 交给 AI。
        if (!root.has("success")) {
            return StepResult.success(toMap(root));
        }

        boolean success = root.get("success").getAsBoolean();
        String message = root.has("message") && !root.get("message").isJsonNull()
                ? root.get("message").getAsString() : "";
        boolean timedOut = root.has("timed_out") && root.get("timed_out").getAsBoolean();
        boolean interrupted = root.has("interrupted") && root.get("interrupted").getAsBoolean();

        Map<String, Object> data = new LinkedHashMap<>();
        if (root.has("data") && root.get("data").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("data").entrySet()) {
                data.put(e.getKey(), toValue(e.getValue()));
            }
        }

        if (timedOut || interrupted) {
            return StepResult.paused(message == null || message.isBlank()
                    ? tool + " 未完成(" + (timedOut ? "超时" : "中断") + ")" : message, data);
        }
        boolean asyncAccepted = Boolean.TRUE.equals(data.get("async"));
        if (success && asyncAccepted) {
            return StepResult.paused("async task accepted: " + message, data);
        }
        if (success) return StepResult.success(data);
        return StepResult.failed(message == null || message.isBlank() ? tool + " failed" : message);
    }

    private static Map<String, Object> toMap(JsonObject obj) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            out.put(e.getKey(), toValue(e.getValue()));
        }
        return out;
    }

    private static Object toValue(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.floor(d) && Double.isFinite(d) && Math.abs(d) < 9.007199254740992E15) {
                    return (long) d;
                }
                return d;
            }
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) list.add(toValue(item));
            return list;
        }
        if (e.isJsonObject()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                obj.put(entry.getKey(), toValue(entry.getValue()));
            }
            return obj;
        }
        return null;
    }
}
