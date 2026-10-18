package com.dwinovo.numen.worldstate.core.json;

import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 观测存储的持久化编解码（Gson，与全树同一套）。按世界存档隔离，带 schema 版本号。
 *
 * <p><b>损坏的 JSON 不抛异常</b>：返回空表——一个可选的观测缓存坏掉，绝不该拖垮插件启动
 * （与 {@code SettlementJson} / {@code RddAssetStore} 同纪律）。未知主题的载荷整条丢弃
 * （向前兼容：老版本读新版本写的数据时干净降级，而不是崩）。
 *
 * <p>重启后载入的观测一律标 {@link Freshness#HISTORICAL}：它们是上个进程看到的，
 * 不能冒充本轮实时结果，要读当前内容必须刷新。
 *
 * <p>这里只管"字节 ↔ 对象"，落盘目录与原子替换由宿主侧（{@code plugins/worldstate}）负责。
 */
public final class ObservationJson {

    public static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private ObservationJson() {
    }

    private record Envelope(int version, List<StoredObservation> observations) {
    }

    private record StoredObservation(String worldId, String dimension, String topic, String target,
                                     String filter, JsonElement payload, String source, String origin,
                                     long gameTick, long epochMs, String coverage, long revision,
                                     int dataVersion) {
    }

    public static String toJson(List<Observation> observations) {
        List<StoredObservation> stored = new ArrayList<>();
        if (observations != null) {
            for (Observation o : observations) {
                StoredObservation s = toStored(o);
                if (s != null) stored.add(s);
            }
        }
        return GSON.toJson(new Envelope(SCHEMA_VERSION, stored));
    }

    /** 解析持久化串；格式不合/损坏返回空表（不抛）。 */
    public static List<Observation> fromJson(String json) {
        List<Observation> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Envelope envelope;
        try {
            envelope = GSON.fromJson(json, Envelope.class);
        } catch (RuntimeException e) {
            return out;
        }
        if (envelope == null || envelope.observations() == null) return out;
        for (StoredObservation s : envelope.observations()) {
            Observation o = fromStored(s);
            if (o != null) out.add(o);
        }
        return out;
    }

    private static StoredObservation toStored(Observation o) {
        if (o == null || o.key() == null || o.payload() == null) return null;
        ObservationKey k = o.key();
        return new StoredObservation(k.worldId(), k.dimension(), k.topic().id(), k.target(), k.filter(),
                GSON.toJsonTree(o.payload()), o.source(), o.origin() == null ? null : o.origin().name(),
                o.gameTick(), o.epochMs(), o.coverage() == null ? null : o.coverage().name(),
                o.revision(), o.dataVersion());
    }

    private static Observation fromStored(StoredObservation s) {
        if (s == null) return null;
        Topic topic = Topic.byId(s.topic());
        if (topic == null) return null;
        ObservationPayload payload = decodePayload(topic, s.payload());
        if (payload == null) return null;
        ObservationKey key = ObservationKey.of(s.worldId(), s.dimension(), topic, s.target(), s.filter());
        return new Observation(key, payload, s.source(), parseOrigin(s.origin()),
                s.gameTick(), s.epochMs(), parseCoverage(s.coverage()),
                Freshness.HISTORICAL, s.revision(), s.dataVersion());
    }

    /** 按主题解码载荷；未知主题或解码失败返回 null（整条丢弃）。 */
    private static ObservationPayload decodePayload(Topic topic, JsonElement payload) {
        if (payload == null || payload.isJsonNull()) return null;
        try {
            return switch (topic) {
                case ENVIRONMENT -> GSON.fromJson(payload, EnvironmentPayload.class);
                case ENTITIES -> GSON.fromJson(payload, EntityCountPayload.class);
                // 其余主题尚未接入采集器，载荷形状未定；遇到就丢弃，向前兼容。
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Coverage parseCoverage(String name) {
        if (name == null) return null;
        try {
            return Coverage.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Origin parseOrigin(String name) {
        if (name == null) return Origin.DIRECT;
        try {
            return Origin.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Origin.DIRECT;
        }
    }

    /** 供测试与诊断：确认某主题的载荷能被本编解码器处理。 */
    public static boolean canDecode(Topic topic) {
        return topic == Topic.ENVIRONMENT || topic == Topic.ENTITIES;
    }
}
