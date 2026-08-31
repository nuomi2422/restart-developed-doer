package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AC 内容的 canonical fingerprint。把“执行身份”从文本 version 提升到内容级：
 * 同一 version 下内容被改，fingerprint 也会变；{@code resume} 据此拒绝旧断点
 * 的静默续跑。
 *
 * <p>canonical 规则：参数 Map 按 key 排序；数字归一（{@code 1} 与 {@code 1.0}
 * 视为同一）；步骤保序。因此同一份 AC 无论来源写法如何，同内容同 fingerprint。
 */
public final class AcFingerprint {

    private AcFingerprint() {}

    /** 计算一个 AC 的 SHA-256 fingerprint（基于 name + version + steps + canonical 参数）。 */
    public static String of(AcDefinition ac) {
        JsonObject root = new JsonObject();
        root.addProperty("name", ac.name());
        root.addProperty("version", ac.version());
        JsonArray steps = new JsonArray();
        for (AcDefinition.AcStep step : ac.steps()) {
            JsonObject s = new JsonObject();
            s.addProperty("id", step.id());
            s.addProperty("tool", step.tool());
            s.add("parameters", canon(step.parameters()));
            steps.add(s);
        }
        root.add("steps", steps);
        String canonical = new GsonBuilder().disableHtmlEscaping().create().toJson(root);
        return sha256(canonical);
    }

    /** 任意参数值 → canonical JsonElement（Map 排序、数字归一）。 */
    private static JsonElement canon(Object value) {
        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                obj.add(e.getKey(), canon(e.getValue()));
            }
            return obj;
        }
        if (value instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list) arr.add(canon(item));
            return arr;
        }
        if (value instanceof Number n) return new JsonPrimitive(normalize(n));
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value == null) return JsonNull.INSTANCE;
        return new JsonPrimitive(String.valueOf(value));
    }

    /** 整数/小数归一：1 与 1.0 同一；整数用 long，非整数用去尾零 BigDecimal。 */
    private static Number normalize(Number n) {
        double d = n.doubleValue();
        if (Double.isFinite(d) && d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) {
            return d == 0 ? 0L : (long) d;
        }
        return new BigDecimal(n.toString()).stripTrailingZeros();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
