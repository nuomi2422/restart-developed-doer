package com.dwinovo.numen.acx.core;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxRecordStore;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStep;
import com.dwinovo.numen.acx.api.AcxToolRegistry;

/**
 * JSONL 执行记录落盘。契约号 ACX-R1 的实现。
 *
 * <p>一行一条记录，追加写。只对 PAUSED / FAIL / TIMEOUT 落盘（正常路径不淹日志），
 * 由 {@link AcxRunner} 决定调用时机。</p>
 *
 * <p><b>为什么不用 gson 的对象序列化</b>：记录里有 {@code Object} 类型字段
 * （progress 里可能有 Boolean 或 Double），gson 反序列化回来会变成
 * {@code LinkedTreeMap} / {@code Double}，再序列化就变形。统一用
 * {@link #gson()} 把 {@code Map} 转 JSON 文本，字段顺序稳定。</p>
 */
public final class JsonlRecordStore implements AcxRecordStore, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(JsonlRecordStore.class.getName());

    private final Path file;
    private final Object lock = new Object();
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private Writer writer;
    private final int memoryCap;
    private final List<Map<String, Object>> memory = new ArrayList<>();

    public JsonlRecordStore(Path file) {
        this(file, 200);
    }

    public JsonlRecordStore(Path file, int memoryCap) {
        this.file = file;
        this.memoryCap = memoryCap;
    }

    public Path file() {
        return file;
    }

    @Override
    public void append(AcxRunRecord record) {
        if (!record.status().needsDurableRecord()) {
            // SUCCESS 不落盘（避免正常路径淹日志）；全量计数由 AcxQualityLedger 承担
            return;
        }
        Map<String, Object> row = record.toMap();
        synchronized (lock) {
            memory.add(row);
            while (memory.size() > memoryCap) {
                memory.remove(0);
            }
            try {
                if (writer == null) {
                    Path parent = file.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                writer.write(gson.toJson(row));
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                LOG.warning("acx 记录写入失败 " + file + ": " + e.getMessage());
            }
        }
    }

    /** 最近 N 条（内存镜像），供 {@code acx_status} / 事故复盘直接读，不碰盘。 */
    public List<Map<String, Object>> recent() {
        synchronized (lock) {
            return new ArrayList<>(memory);
        }
    }

    /** 最近一条 PAUSED 记录（断点续跑的入口）。 */
    public Map<String, Object> latestPaused() {
        synchronized (lock) {
            for (int i = memory.size() - 1; i >= 0; i--) {
                Map<String, Object> row = memory.get(i);
                if ("PAUSED".equals(String.valueOf(row.get("status")))) {
                    return new LinkedHashMap<>(row);
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已经 flush 的内容
                }
                writer = null;
            }
        }
    }

    // ── 共用小工具：Map → JSON 文本 / JSON 文本 → Map ────────────────────

    public static Gson gson() {
        return new GsonBuilder().serializeNulls().create();
    }

    public static String toJson(Object value) {
        return gson().toJson(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        JsonElement el = JsonParser.parseString(json);
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("顶层不是 JSON 对象");
        }
        return new LinkedHashMap<>((Map<String, Object>) gson().fromJson(el, Map.class));
    }

    /** 把任意事件转成一行 JSON（监测台 jsonl 用）。 */
    public static String eventToJson(AcxEvent event) {
        return gson().toJson(event.toMap());
    }

    /** 步骤深度的 {@code block} 列表（诊断 / 指纹用）。 */
    public static List<String> blockNames(List<AcxStep> steps) {
        List<String> out = new ArrayList<>();
        for (AcxStep s : steps) {
            out.add(s.block());
        }
        return out;
    }

    /** 供 {@link AcxLoader} 复用：AC 名字 → 定义。 */
    public static AcxCatalog catalogOf(Map<String, AcxDefinition> defs) {
        return AcxCatalog.of(defs);
    }

    /** 供宿主桥接复用：把 Numen 侧注册表包成 ACX 目录（加载期校验用）。 */
    public static AcxToolRegistry requireRegistry(AcxToolRegistry registry) {
        if (registry == null) {
            throw new IllegalStateException("积木注册表不能为 null");
        }
        return registry;
    }
}
