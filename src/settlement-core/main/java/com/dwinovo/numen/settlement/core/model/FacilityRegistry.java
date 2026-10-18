package com.dwinovo.numen.settlement.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设施登记表：设施模块的<b>登记真源</b>，按固定 id 索引。
 *
 * <p>不放进 {@code AssetRegistry}：那里有 128 条淘汰上限，会把"永久登记的基地和保护规则"
 * 跟着普通观测一起淘汰。真源必须独立持久化（见
 * {@link com.dwinovo.numen.settlement.core.json.SettlementJson}）。
 *
 * <p>写成不可变风格（{@code with}/{@code without} 返回新表）：读侧（保护判定、规划路径）
 * 拿到的快照不会被写侧改动，跨线程读安全。写侧整份替换插件里持有的引用即可。
 */
public final class FacilityRegistry {

    private final Map<String, FacilityRecord> byId;

    public FacilityRegistry() {
        this.byId = new LinkedHashMap<>();
    }

    private FacilityRegistry(Map<String, FacilityRecord> byId) {
        this.byId = new LinkedHashMap<>(byId);
    }

    public static FacilityRegistry of(List<FacilityRecord> records) {
        FacilityRegistry registry = new FacilityRegistry();
        if (records != null) {
            for (FacilityRecord r : records) {
                if (r != null) registry.byId.put(r.id(), r);
            }
        }
        return registry;
    }

    /** 登记或覆盖一处设施，返回新表。 */
    public FacilityRegistry with(FacilityRecord record) {
        if (record == null) throw new IllegalArgumentException("record required");
        Map<String, FacilityRecord> next = new LinkedHashMap<>(byId);
        next.put(record.id(), record);
        return new FacilityRegistry(next);
    }

    /** 注销一处设施，返回新表。 */
    public FacilityRegistry without(String id) {
        Map<String, FacilityRecord> next = new LinkedHashMap<>(byId);
        next.remove(id);
        return new FacilityRegistry(next);
    }

    public List<FacilityRecord> all() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public Optional<FacilityRecord> byId(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    /** 同一维度的全部设施（保护判定按维度先缩范围）。 */
    public List<FacilityRecord> inDimension(String dimension) {
        if (dimension == null) return List.of();
        List<FacilityRecord> out = new ArrayList<>();
        for (FacilityRecord r : byId.values()) {
            if (r.inDimension(dimension)) out.add(r);
        }
        return out;
    }

    /** 占地命中某列的设施（"这一列属于谁"）。 */
    public List<FacilityRecord> coveringColumn(String dimension, int x, int z) {
        List<FacilityRecord> out = new ArrayList<>();
        for (FacilityRecord r : inDimension(dimension)) {
            if (r.coversColumn(x, z)) out.add(r);
        }
        return out;
    }
}
