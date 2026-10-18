package com.dwinovo.numen.settlement.core.json;

import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

/**
 * 设施登记表的持久化编解码（Gson，与全树其余模块同一套）。
 *
 * <p>带 schema 版本号，方便将来迁移。损坏的 JSON <b>不抛异常</b>：返回空表——
 * 一个可选的登记缓存坏掉，绝不该拖垮任务链恢复（与 {@code RddAssetStore} 同纪律）。
 *
 * <p>这里只负责"字节 ↔ 对象"，落盘目录与原子替换由宿主侧（{@code plugins/settlement}）负责。
 */
public final class SettlementJson {

    private static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private SettlementJson() {}

    private record Envelope(int version, List<FacilityRecord> facilities) {}

    public static String toJson(FacilityRegistry registry) {
        List<FacilityRecord> facilities = registry == null ? List.of() : registry.all();
        return GSON.toJson(new Envelope(SCHEMA_VERSION, facilities));
    }

    public static FacilityRegistry fromJson(String json) {
        if (json == null || json.isBlank()) return new FacilityRegistry();
        try {
            Envelope envelope = GSON.fromJson(json, Envelope.class);
            if (envelope == null || envelope.facilities() == null) return new FacilityRegistry();
            return FacilityRegistry.of(envelope.facilities());
        } catch (RuntimeException e) {
            return new FacilityRegistry();
        }
    }
}
