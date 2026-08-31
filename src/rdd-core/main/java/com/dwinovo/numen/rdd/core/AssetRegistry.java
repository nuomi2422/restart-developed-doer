package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import java.util.*;

public final class AssetRegistry {
    private final Map<String, AssetEntry> current = new LinkedHashMap<>();
    private final List<Observation> history = new ArrayList<>();

    public synchronized boolean apply(Observation observation, String assetId, AssetScope scope, String originTaskNodeId) {
        Objects.requireNonNull(observation);
        Objects.requireNonNull(assetId);
        Objects.requireNonNull(scope);
        if (assetId.isBlank()) throw new IllegalArgumentException("assetId required");
        AssetEntry next = new AssetEntry(assetId, observation, AssetStatus.OBSERVED, scope, originTaskNodeId);
        AssetEntry previous = current.put(assetId, next);
        history.add(observation);
        return !next.equals(previous);
    }

    public synchronized void markUnknown(String assetId) {
        AssetEntry entry = require(assetId);
        current.put(assetId, entry.withStatus(AssetStatus.UNKNOWN));
    }

    public synchronized void invalidate(String assetId) {
        AssetEntry entry = require(assetId);
        current.put(assetId, entry.withStatus(AssetStatus.INVALID));
    }

    public synchronized Optional<AssetEntry> get(String assetId) { return Optional.ofNullable(current.get(assetId)); }
    public synchronized List<AssetEntry> snapshot() { return List.copyOf(current.values()); }
    public synchronized List<Observation> history() { return List.copyOf(history); }
    public synchronized List<AssetEntry> usable() {
        return current.values().stream().filter(e -> e.status() == AssetStatus.OBSERVED).toList();
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
