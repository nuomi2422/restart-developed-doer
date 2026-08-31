package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 严格 AC JSON 解析：每个字段做类型检查并给出稳定诊断；参数递归转换
 * （Map/List/String/Number/Boolean/null），带深度上限。
 */
public final class AcJson {

    /** 参数 JSON 最大嵌套深度。 */
    public static final int MAX_PARAM_DEPTH = 8;

    private AcJson() {}

    public static AcDefinition load(Reader reader) {
        if (reader == null) throw new IllegalArgumentException("reader required");
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (Exception e) {
            throw new IllegalArgumentException("AC JSON 解析失败: " + e.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("AC 必须是 JSON 对象");
        }
        JsonObject obj = root.getAsJsonObject();

        String name = requireString(obj, "name");
        String version = requireString(obj, "version");
        if (!obj.has("steps") || !obj.get("steps").isJsonArray()) {
            throw new IllegalArgumentException("AC 缺少 steps 数组");
        }
        JsonArray stepsArr = obj.getAsJsonArray("steps");
        if (stepsArr.isEmpty()) throw new IllegalArgumentException("steps 不能为空");

        List<AcDefinition.AcStep> steps = new ArrayList<>();
        for (JsonElement e : stepsArr) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("每个 step 必须是 JSON 对象");
            JsonObject s = e.getAsJsonObject();
            String id = requireString(s, "id");
            String tool = requireString(s, "tool");
            Map<String, Object> params = s.has("parameters") ? parseParams(s.get("parameters"), 0) : Map.of();
            steps.add(new AcDefinition.AcStep(id, tool, params));
        }
        return new AcDefinition(name, version, steps);
    }

    private static String requireString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("AC " + key + " 字段必须是字符串");
        }
        return obj.get(key).getAsString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseParams(JsonElement element, int depth) {
        if (depth > MAX_PARAM_DEPTH) throw new IllegalArgumentException("参数嵌套过深");
        if (!element.isJsonObject()) throw new IllegalArgumentException("parameters 必须是 JSON 对象");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            out.put(entry.getKey(), toValue(entry.getValue(), depth));
        }
        return out;
    }

    private static Object toValue(JsonElement e, int depth) {
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
            if (depth + 1 > MAX_PARAM_DEPTH) throw new IllegalArgumentException("参数嵌套过深");
            List<Object> list = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) list.add(toValue(item, depth + 1));
            return list;
        }
        if (e.isJsonObject()) {
            if (depth + 1 > MAX_PARAM_DEPTH) throw new IllegalArgumentException("参数嵌套过深");
            return parseParams(e, depth + 1);
        }
        return null;
    }
}
