package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.AcVersionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 AC 版本仓储：按 name → (version → AcDefinition) 组织，版本号按字典序排序。
 * 线程安全；{@code AcDefinition} 不可变，put 原子。
 */
public final class InMemoryAcVersionStore implements AcVersionStore {

    private final Map<String, Map<String, AcDefinition>> byName = new ConcurrentHashMap<>();

    @Override
    public void put(String name, String version, AcDefinition ac) {
        byName.computeIfAbsent(name, k -> new TreeMap<>()).put(version, ac);
    }

    @Override
    public Optional<AcDefinition> get(String name, String version) {
        Map<String, AcDefinition> versions = byName.get(name);
        return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
    }

    @Override
    public Optional<AcDefinition> latest(String name) {
        Map<String, AcDefinition> versions = byName.get(name);
        if (versions == null || versions.isEmpty()) return Optional.empty();
        return Optional.of(versions.get(versions.keySet().stream().reduce((a, b) -> b).orElseThrow()));
    }

    @Override
    public List<String> versions(String name) {
        Map<String, AcDefinition> versions = byName.get(name);
        return versions == null ? List.of() : List.copyOf(new ArrayList<>(versions.keySet()));
    }
}
