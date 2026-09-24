package com.dwinovo.numen.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Per-call observation, with no Minecraft dependency and no control over execution.
 * Credentials/hidden reasoning are excluded from the copy; the actual wire body is never changed. */
public final class LlmObservation {
    private final String requestId = UUID.randomUUID().toString();
    private final String actor, companionId, phase;
    private final BiConsumer<String, Map<String, ?>> sink;

    public LlmObservation(String actor, String companionId, String phase,
                          BiConsumer<String, Map<String, ?>> sink) {
        this.actor = actor;
        this.companionId = companionId;
        this.phase = phase;
        this.sink = sink;
    }

    void publish(String type, String model, Map<String, ?> fields, String secret) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("actor", actor);
            data.put("companionId", companionId);
            data.put("requestId", requestId);
            data.put("phase", phase);
            data.put("model", model);
            data.put("observationVersion", 1);
            data.put("excluded", java.util.List.of("credentials", "hidden_reasoning"));
            for (var field : fields.entrySet()) {
                Object value = field.getValue();
                data.put(field.getKey(), value instanceof JsonElement json
                        ? visibleCopy(json, secret) : value);
            }
            sink.accept(type, data);
        } catch (RuntimeException ignored) {
            // Monitoring must never fail a request or its completion callback.
        }
    }

    /** Preserve full visible messages and tools, suppress provider-internal reasoning blocks.
     * Thinking mode/configuration stays visible; only generated reasoning text is excluded. */
    static JsonElement visibleCopy(JsonElement value, String secret) {
        return visibleCopy(value, secret, false);
    }

    private static JsonElement visibleCopy(JsonElement value, String secret, boolean toolSchema) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                JsonElement blockType = item.isJsonObject() ? item.getAsJsonObject().get("type") : null;
                if (!toolSchema && blockType != null && blockType.isJsonPrimitive()
                        && blockType.getAsJsonPrimitive().isString()) {
                    String type = blockType.getAsString();
                    if (type.equals("thinking") || type.equals("redacted_thinking") || type.equals("reasoning")) continue;
                }
                out.add(visibleCopy(item, secret, toolSchema));
            }
            return out;
        }
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) {
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!toolSchema && java.util.Set.of("reasoning_content", "reasoning_text", "reasoning_details", "signature", "authorization",
                        "api_key", "apikey", "x-api-key", "access_token", "refresh_token").contains(key)) continue;
                if (!toolSchema && key.equals("reasoning") && entry.getValue().isJsonPrimitive()) continue;
                out.add(entry.getKey(), visibleCopy(entry.getValue(), secret, toolSchema || key.equals("tools")));
            }
            return out;
        }
        if (value.getAsJsonPrimitive().isString() && secret != null && !secret.isBlank()) {
            return new JsonPrimitive(value.getAsString().replace(secret, "[credential removed]"));
        }
        return value.deepCopy();
    }
}
