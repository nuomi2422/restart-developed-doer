package com.dwinovo.numen.acx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.dwinovo.numen.acx.api.AcxRecordStore;
import com.dwinovo.numen.acx.api.AcxRunRecord;

/**
 * 质量台账：{@link AcxRecordStore} 的全量实现 —— 每条终态记录都计数（含 SUCCESS）。
 *
 * <p>JsonlRecordStore 只把 PAUSED/FAIL/TIMEOUT 写文件（避免正常路径淹日志）；
 * 台账拿到的却是全部终态，所以它同时承担两件事：</p>
 * <ul>
 *   <li><b>每版本运行标记</b>：{@code hasRun(name, version)} / {@code lastRunAt}；
 *       「这个版本到底跑过没有」不靠记忆，靠台账。</li>
 *   <li><b>质量统计</b>：成功率 / 平均耗时 / 停滞次数 / 最后状态，供晋级判定与 acx_status。</li>
 * </ul>
 *
 * <p>键是 {@code name@vversion}（版本维度）；{@code statsOf(name)} 聚合该 AC 所有版本。</p>
 *
 * <p>台账默认在内存；{@link #save(Path)} / {@link #load(Path)} 负责持久化（tmp + ATOMIC_MOVE），
 * 由宿主决定存哪、多久存一次。文件坏了按空台账并记警告，绝不让观测数据反过来阻挡执行。</p>
 */
public final class AcxQualityLedger implements AcxRecordStore {

    private static final Logger LOG = Logger.getLogger(AcxQualityLedger.class.getName());

    private final Map<String, AcxRunStats> byVersion = new LinkedHashMap<>();

    public static String key(String name, String version) {
        return name + "@v" + version;
    }

    @Override
    public synchronized void append(AcxRunRecord record) {
        if (record == null) {
            return;
        }
        byVersion.computeIfAbsent(key(record.acName(), record.acVersion()), k -> new AcxRunStats())
                .accept(record);
    }

    /** 单版本统计；没跑过返回 null。 */
    public synchronized AcxRunStats stats(String name, String version) {
        return byVersion.get(key(name, version));
    }

    /** 跨版本聚合；一个版本都没跑过返回 null。 */
    public synchronized AcxRunStats statsOf(String name) {
        String prefix = name + "@v";
        AcxRunStats agg = null;
        for (Map.Entry<String, AcxRunStats> e : byVersion.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            if (agg == null) {
                agg = new AcxRunStats();
            }
            agg.merge(e.getValue());
        }
        return agg;
    }

    /** 每版本运行标记：这个版本跑过没有。 */
    public synchronized boolean hasRun(String name, String version) {
        AcxRunStats s = byVersion.get(key(name, version));
        return s != null && s.runs() > 0;
    }

    public synchronized int runs(String name, String version) {
        AcxRunStats s = byVersion.get(key(name, version));
        return s == null ? 0 : s.runs();
    }

    public synchronized long lastRunAt(String name, String version) {
        AcxRunStats s = byVersion.get(key(name, version));
        return s == null ? 0 : s.lastRunAt();
    }

    public synchronized List<String> keys() {
        return new ArrayList<>(byVersion.keySet());
    }

    /** 全量快照（key → stats map），供 acx_status / 监测台。 */
    public synchronized Map<String, Map<String, Object>> all() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, AcxRunStats> e : byVersion.entrySet()) {
            out.put(e.getKey(), e.getValue().toMap());
        }
        return out;
    }

    public synchronized String summary() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, AcxRunStats> e : byVersion.entrySet()) {
            lines.add(e.getKey() + " → " + e.getValue().summary());
        }
        return lines.isEmpty() ? "台账为空（还没有任何执行）" : String.join("\n", lines);
    }

    public synchronized void reset() {
        byVersion.clear();
    }

    // ── 持久化（可选；宿主要求跑一段存一段时用）─────────────────────────

    public synchronized void save(Path file) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("stats", all());
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JsonlRecordStore.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("[acx] 台账落盘失败: " + e.getMessage());
            throw new IllegalStateException("台账落盘失败: " + e.getMessage(), e);
        }
    }

    public synchronized void load(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Map<String, Object> root = JsonlRecordStore.toMap(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root.get("stats") instanceof Map<?, ?> stats)) {
                return;
            }
            for (Map.Entry<?, ?> e : stats.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                byVersion.put(String.valueOf(e.getKey()), AcxRunStats.fromMap(mm));
            }
        } catch (Exception e) {
            LOG.warning("[acx] 台账读取失败（按空台账处理）: " + e.getMessage());
        }
    }
}
