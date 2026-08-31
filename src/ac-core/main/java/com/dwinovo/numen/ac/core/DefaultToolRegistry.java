package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcTool;
import com.dwinovo.numen.ac.api.ToolRegistry;
import com.dwinovo.numen.ac.api.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的默认工具注册表。工具名按字典序排序，目录稳定可读。
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, AcTool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolSchema> schemas = new ConcurrentHashMap<>();

    @Override
    public void register(String name, AcTool tool) {
        register(name, tool, new ToolSchema(name, "1", "", Map.of()));
    }

    @Override
    public void register(String name, AcTool tool, ToolSchema schema) {
        if (name == null || name.isBlank() || tool == null || schema == null || !name.equals(schema.name())) {
            throw new IllegalArgumentException("tool name, implementation and matching schema required");
        }
        if (tools.putIfAbsent(name, tool) != null) {
            throw new IllegalArgumentException("duplicate tool: " + name);
        }
        schemas.put(name, schema);
    }

    @Override
    public Optional<AcTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Optional<ToolSchema> schema(String name) {
        return Optional.ofNullable(schemas.get(name));
    }

    @Override
    public List<String> toolNames() {
        return List.copyOf(new TreeMap<>(tools).keySet());
    }

    @Override
    public List<ToolSchema> schemas() {
        List<ToolSchema> out = new ArrayList<>();
        for (String name : toolNames()) {
            ToolSchema s = schemas.get(name);
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    @Override
    public boolean contains(String name) {
        return tools.containsKey(name);
    }
}
