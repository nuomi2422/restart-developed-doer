package com.dwinovo.numen.rdd.policy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 资产用途/储备映射（P2.1）：回答“这件东西为什么重要、要留几件”。
 *
 * <p>**刻意不塞进 AssetEntry**：AssetEntry 是“世界事实”（有 3 件钻石甲），
 * 本层是“规划语义”（1 件在穿、2 件备用）。两者生命周期不同，混在一起会污染事实持久化格式。
 *
 * <p>用途示例：{@code setReserved("minecraft:diamond_chestplate", DEATH_RECOVERY, 2)}。
 * 死亡恢复时不再问“有没有钻石甲”，而是问“有没有 DEATH_RECOVERY 用途的钻石甲（且够数）”。
 *
 * <p>纯 JVM，可 JSON 往返；线程安全（synchronized）。
 */
public final class AssetPurposeStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, Map<AssetRole, Integer>> reserved = new LinkedHashMap<>();

    /** 某类资产某用途要求“至少保留”的件数（未设置=0）。 */
    public synchronized int reservedOf(String assetType, AssetRole role) {
        if (assetType == null || role == null) {
            return 0;
        }
        Map<AssetRole, Integer> m = reserved.get(assetType);
        return m == null ? 0 : m.getOrDefault(role, 0);
    }

    /** 设置某类资产某用途的保留件数（<0 视作 0）。 */
    public synchronized void setReserved(String assetType, AssetRole role, int count) {
        Objects.requireNonNull(assetType, "assetType required");
        Objects.requireNonNull(role, "role required");
        reserved.computeIfAbsent(assetType, k -> new LinkedHashMap<>()).put(role, Math.max(0, count));
    }

    /** 现有数量是否满足该用途的最低保留（available >= reserved）。 */
    public synchronized boolean hasReserved(String assetType, AssetRole role, int available) {
        return available >= reservedOf(assetType, role);
    }

    /** 某类资产的用途快照（不可变）。 */
    public synchronized Map<AssetRole, Integer> rolesOf(String assetType) {
        Map<AssetRole, Integer> m = reserved.get(assetType);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<AssetRole, Integer>> e : reserved.entrySet()) {
            JsonObject roles = new JsonObject();
            for (Map.Entry<AssetRole, Integer> r : e.getValue().entrySet()) {
                roles.addProperty(r.getKey().name(), r.getValue());
            }
            root.add(e.getKey(), roles);
        }
        return GSON.toJson(root);
    }

    public static AssetPurposeStore fromJson(String json) {
        AssetPurposeStore store = new AssetPurposeStore();
        if (json == null || json.isBlank()) {
            return store;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (String type : root.keySet()) {
                JsonObject roles = root.getAsJsonObject(type);
                for (String roleName : roles.keySet()) {
                    store.setReserved(type, AssetRole.valueOf(roleName), roles.get(roleName).getAsInt());
                }
            }
        } catch (RuntimeException ex) {
            return new AssetPurposeStore();
        }
        return store;
    }
}
