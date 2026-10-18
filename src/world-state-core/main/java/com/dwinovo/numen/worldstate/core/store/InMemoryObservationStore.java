package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存观测存储：频繁读取的当前观测，带容量上限的 LRU 淘汰。
 *
 * <p>返回的 {@link Observation} 是不可变 record，读侧天然拿到不可变快照，不会读到写一半的数据。
 * 所有操作在同一把锁下完成；{@link #all()} 返回副本。
 *
 * <p>容量淘汰按 LRU；被淘汰只是丢了缓存，不影响任何登记真源（设施登记在 settlement 自己那边）。
 */
public final class InMemoryObservationStore implements ObservationStore {

    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Map<String, Observation> map;

    public InMemoryObservationStore() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryObservationStore(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.map = new LinkedHashMap<>(16, 0.75f, true); // accessOrder = true → LRU
    }

    @Override
    public synchronized Optional<Observation> get(ObservationKey key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(map.get(key.canonical()));
    }

    @Override
    public synchronized boolean put(Observation observation) {
        if (observation == null || observation.key() == null) return false;
        String k = observation.key().canonical();
        Observation current = map.get(k);
        if (current != null && current.revision() > observation.revision()) {
            // 迟到的旧刷新：拒绝覆盖更新的观测。
            return false;
        }
        map.put(k, observation);
        evictIfNeeded();
        return true;
    }

    @Override
    public synchronized List<Observation> all() {
        return new ArrayList<>(map.values());
    }

    @Override
    public synchronized int size() {
        return map.size();
    }

    @Override
    public synchronized void clear() {
        map.clear();
    }

    private void evictIfNeeded() {
        while (map.size() > capacity) {
            String eldest = map.keySet().iterator().next();
            map.remove(eldest);
        }
    }
}
