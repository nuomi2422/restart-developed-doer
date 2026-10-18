package com.dwinovo.numen.rdd.side;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 支线状态唯一所有者：每个同伴<b>同时最多一条</b>活跃支线。
 *
 * <p>与 {@code TaskChain}（主线唯一所有者）并列，互不写对方。持久化用 JSON（随宿主落盘）。
 */
public final class SideTaskRuntime {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<UUID, SideTask> active = new LinkedHashMap<>();
    private long generation;

    public Optional<SideTask> current(UUID companionId) {
        return Optional.ofNullable(active.get(companionId));
    }

    public boolean isActive(UUID companionId) {
        SideTask task = active.get(companionId);
        return task != null && !task.terminal();
    }

    /** 单调递增的实例代次；每次真正创建一条支线时取一次。 */
    public long nextGeneration() {
        return ++generation;
    }

    public long generation() {
        return generation;
    }

    /** 激活一条支线；已有活跃支线时拒绝（同一同伴只允许一个 active）。 */
    public boolean activate(SideTask task) {
        if (isActive(task.companionId())) {
            return false;
        }
        active.put(task.companionId(), task);
        generation = Math.max(generation, task.generation());
        return true;
    }

    /** 原地替换（推进阶段/负载）；不改变实例身份。 */
    public SideTask replace(SideTask task) {
        active.put(task.companionId(), task);
        return task;
    }

    public Optional<SideTask> remove(UUID companionId) {
        return Optional.ofNullable(active.remove(companionId));
    }

    public void clearAll() {
        active.clear();
    }

    public Map<UUID, SideTask> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(active));
    }

    // ---- 持久化 ----

    public JsonObject toJsonObject() {
        JsonObject o = new JsonObject();
        o.addProperty("generation", generation);
        JsonArray arr = new JsonArray();
        for (SideTask task : active.values()) {
            arr.add(GSON.toJsonTree(task, SideTask.class));
        }
        o.add("active", arr);
        return o;
    }

    public void restore(JsonObject o) {
        active.clear();
        if (o == null) {
            return;
        }
        generation = o.has("generation") ? o.get("generation").getAsLong() : 0L;
        if (o.has("active")) {
            for (JsonElement el : o.getAsJsonArray("active")) {
                SideTask task = GSON.fromJson(el, SideTask.class);
                if (task != null && task.companionId() != null) {
                    active.put(task.companionId(), task);
                    generation = Math.max(generation, task.generation());
                }
            }
        }
    }

    public String toJson() {
        return GSON.toJson(toJsonObject());
    }

    public static SideTaskRuntime fromJson(String json) {
        SideTaskRuntime runtime = new SideTaskRuntime();
        if (json != null && !json.isBlank()) {
            runtime.restore(JsonParser.parseString(json).getAsJsonObject());
        }
        return runtime;
    }
}
