package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

public final class AssetRegistry {
    private static final int MAX_HISTORY = 512;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, AssetEntry> current = new LinkedHashMap<>();
    private final List<Observation> history = new ArrayList<>();

    public synchronized boolean apply(Observation observation, String assetId, AssetScope scope, String originTaskNodeId) {
        Objects.requireNonNull(observation);
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(scope);
        if (assetId.isBlank()) throw new IllegalArgumentException("assetId required");
        AssetEntry next = new AssetEntry(assetId, observation, AssetStatus.OBSERVED, scope, originTaskNodeId);
        AssetEntry previous = current.remove(assetId);
        current.put(assetId, next);
        history.add(observation);
        if (history.size() > MAX_HISTORY) history.removeFirst();
        return !next.equals(previous);
    }

    /** Restore one persisted current-state entry without inventing a new observation. */
    public synchronized boolean restore(AssetEntry entry) {
        Objects.requireNonNull(entry);
        AssetEntry previous = current.remove(entry.assetId());
        current.put(entry.assetId(), entry);
        history.add(entry.observation());
        if (history.size() > MAX_HISTORY) history.removeFirst();
        return !entry.equals(previous);
    }

    public synchronized boolean forget(String assetId) {
        if (assetId == null || assetId.isBlank()) return false;
        return current.remove(assetId) != null;
    }

    public synchronized void markUnknown(String assetId) {
        AssetEntry entry = require(assetId);
        current.put(assetId, entry.withStatus(AssetStatus.UNKNOWN));
    }

    public synchronized void invalidate(String assetId) {
        AssetEntry entry = require(assetId);
        current.put(assetId, entry.withStatus(AssetStatus.INVALID));
    }

    /**
     * 批量失效某一观测类型的 OBSERVED 资产（P1 死亡/掉落失效用）：置 INVALID，返回失效条数。
     * 已是非 OBSERVED 的条目不重复计数；非该类型（如 world_ 基地）不受影响——死亡掉的是背包，
     * 不该抹掉已知基地/结构。重新观测同一条目会在 {@link #apply} 里恢复 OBSERVED。
     */
    public synchronized int invalidateByType(String observationType) {
        if (observationType == null || observationType.isBlank()) {
            return 0;
        }
        int changed = 0;
        for (AssetEntry entry : current.values()) {
            if (entry.status() == AssetStatus.OBSERVED && observationType.equals(entry.observation().type())) {
                current.put(entry.assetId(), entry.withStatus(AssetStatus.INVALID));
                changed++;
            }
        }
        return changed;
    }

    public synchronized Optional<AssetEntry> get(String assetId) { return Optional.ofNullable(current.get(assetId)); }
    public synchronized List<AssetEntry> snapshot() { return List.copyOf(current.values()); }
    public synchronized List<Observation> history() { return List.copyOf(history); }
    public synchronized List<AssetEntry> usable() {
        return current.values().stream().filter(e -> e.status() == AssetStatus.OBSERVED).toList();
    }

    /**
     * 当前可用资产按计数折叠成 {@code assetId -> count}，供依赖门与完成判定接入真实注册表。
     * 只有 OBSERVED 且观测为 {@code inventory_scan}（值里有整数 count）的条目才算数；
     * UNKNOWN/INVALID/消耗量为 0 的条目不贡献数量（缺 key 走缺省 0）。
     * 非有限/负值/非整数 count 一概不算可用（与 {@code RddChainFactory#isNonNegativeInteger} 口径一致）。
     * 返回不可变视图，防止调用方污染注册表折叠口径。
     */
    public synchronized Map<String, Integer> usableCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (AssetEntry entry : current.values()) {
            if (entry.status() != AssetStatus.OBSERVED) {
                continue;
            }
            if (!"inventory_scan".equals(entry.observation().type())) {
                continue;
            }
            Object count = entry.observation().value().get("count");
            if (isNonNegativeIntegerCount(count)) {
                counts.put(entry.assetId(), ((Number) count).intValue());
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /** 与 {@code RddChainFactory#isNonNegativeInteger} 同口径：有限、≥0、整数值；1.5/NaN/Infinity/负数全拒。 */
    private static boolean isNonNegativeIntegerCount(Object count) {
        return count instanceof Number n
                && Double.isFinite(n.doubleValue())
                && n.doubleValue() >= 0
                && n.doubleValue() == (double) n.intValue();
    }

    /** Current state only. Observation history is deliberately not persisted. */
    public synchronized String toJson() {
        return GSON.toJson(current.values());
    }

    public static AssetRegistry fromJson(String json) {
        if (json == null || json.isBlank()) return new AssetRegistry();
        AssetEntry[] entries = GSON.fromJson(json, AssetEntry[].class);
        AssetRegistry registry = new AssetRegistry();
        if (entries != null) {
            for (AssetEntry entry : entries) {
                if (entry != null) registry.restore(entry);
            }
        }
        return registry;
    }

    private AssetEntry require(String assetId) {
        if (assetId == null || assetId.isBlank()) throw new IllegalArgumentException("assetId required");
        AssetEntry entry = current.get(assetId);
        if (entry == null) throw new IllegalArgumentException("unknown asset: " + assetId);
        return entry;
    }

    public record AssetEntry(String assetId, Observation observation, AssetStatus status,
                             AssetScope scope, String originTaskNodeId) {
        public AssetEntry {
            if (assetId == null || assetId.isBlank()) throw new IllegalArgumentException("assetId required");
            Objects.requireNonNull(observation); Objects.requireNonNull(status); Objects.requireNonNull(scope);
        }
        AssetEntry withStatus(AssetStatus next) { return new AssetEntry(assetId, observation, next, scope, originTaskNodeId); }
    }
}
