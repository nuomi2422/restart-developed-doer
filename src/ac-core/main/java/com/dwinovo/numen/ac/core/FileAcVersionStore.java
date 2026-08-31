package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.AcVersionStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 落盘版 AC 版本仓储：JSON 持久化到文件，重启恢复。写为原子替换（tmp + move），
 * 崩溃不损坏库。纯 JVM（仅 java.nio + Gson 2.10 原生 record 序列化），路径由宿主提供。
 */
public final class FileAcVersionStore implements AcVersionStore {

    private final Path file;
    private final Map<String, Map<String, AcDefinition>> byName = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public FileAcVersionStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (file == null || !Files.exists(file)) return;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            for (String name : root.keySet()) {
                JsonObject versions = root.getAsJsonObject(name);
                TreeMap<String, AcDefinition> map = new TreeMap<>();
                for (String ver : versions.keySet()) {
                    map.put(ver, gson.fromJson(versions.getAsJsonObject(ver), AcDefinition.class));
                }
                byName.put(name, map);
            }
        } catch (Exception e) {
            System.err.println("[ac-store] 加载失败，从空开始: " + e);
        }
    }

    private void save() {
        try {
            if (file == null) return;
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Map<String, AcDefinition>> e : byName.entrySet()) {
                JsonObject versions = new JsonObject();
                for (Map.Entry<String, AcDefinition> v : e.getValue().entrySet()) {
                    versions.add(v.getKey(), gson.toJsonTree(v.getValue()));
                }
                root.add(e.getKey(), versions);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[ac-store] 保存失败: " + e);
        }
    }

    @Override
    public void put(String name, String version, AcDefinition ac) {
        byName.computeIfAbsent(name, k -> new TreeMap<>()).put(version, ac);
        save();
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
