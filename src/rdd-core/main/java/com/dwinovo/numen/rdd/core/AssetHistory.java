package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * P2.1 资产历史/恢复语义（GPT 外脑建议 · 2026-09-25）。
 *
 * <p>核心命题：<b>Lost ≠ Gone</b>。注册表（{@link AssetRegistry}）只说"现在有没有"，
 * 但规划需要知道"这装备从哪来、丢了能不能回去拿"。死亡把身上的钻石甲判成 INVALID 是
 * 对的，但若规划只看到"没有"，就会把已有基地/备用装备的人重新规划回铁器时代。
 *
 * <p>历史**只记有意义的东西**（不记 dirt/泥土这类）。每条 = 资产 id + 用途 +
 * 最后已知位置 + 最后见到的时间 + 状态。死亡时把当前背包条目记成 LOST（保留位置），
 * 规划提示词于是能说："你现在没有钻石甲，但历史显示据点A 有备用钻石甲×2——先判断能否恢复"。
 *
 * <p>纯 JVM、可单测，不碰 Minecraft 类型（位置用原始坐标 record）。
 */
public final class AssetHistory {

    /** 资产的语义状态。{@code LOST} = 曾存在、暂时不可用（可恢复）；{@code DESTROYED} = 明确没了。 */
    public enum State { CURRENT, LOST, UNKNOWN, DESTROYED }

    /** 资产用途（P2.2 会扩展为完整用途表；这里先承载"丢了影响什么"的最小语义）。 */
    public enum Purpose { BACKUP_EQUIPMENT, FOOD_RESERVE, TOOL, RECOVERY_POINT, UNKNOWN }

    /** 一条历史：某资产的最后已知语义快照。 */
    public record Entry(String assetId, Purpose purpose, State state, Integer lastCount,
                        String dimension, int x, int y, int z, long lastSeenMillis) {

        public Entry {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(state, "state");
            if (purpose == null) purpose = Purpose.UNKNOWN;
        }

        /** 有位置线索 = 可以去恢复。 */
        public boolean hasLocation() {
            return dimension != null && !dimension.isBlank();
        }

        /** 恢复提示串：`minecraft:diamond_chestplate ×2 @ minecraft:overworld(-51,65,-496)`。 */
        public String render() {
            StringBuilder sb = new StringBuilder(assetId);
            if (lastCount != null) sb.append(" ×").append(lastCount);
            if (hasLocation()) {
                sb.append(" @ ").append(dimension).append("(").append(x).append(",").append(y).append(",").append(z).append(")");
            }
            return sb.toString();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** 记一条（同 id 覆盖：取最新状态）。 */
    public void record(Entry entry) {
        if (entry == null || entry.assetId().isBlank()) return;
        entries.put(entry.assetId(), entry);
    }

    /** 死亡/掉落：把一条标成 LOST，保留最后已知位置与用途（核心：Lost≠Gone）。 */
    public void recordLost(String assetId, Purpose purpose, Integer lastCount,
                           String dimension, int x, int y, int z, long nowMillis) {
        if (assetId == null || assetId.isBlank()) return;
        record(new Entry(assetId, purpose, State.LOST, lastCount, dimension, x, y, z, nowMillis));
    }

    /** 再次观测到 = 回到 CURRENT。 */
    public void recordCurrent(String assetId, Purpose purpose, Integer count,
                              String dimension, int x, int y, int z, long nowMillis) {
        if (assetId == null || assetId.isBlank()) return;
        record(new Entry(assetId, purpose, State.CURRENT, count, dimension, x, y, z, nowMillis));
    }

    /** 明确没了（烧毁/被清）→ DESTROYED，不再作为恢复线索。 */
    public void recordDestroyed(String assetId, Purpose purpose, long nowMillis) {
        if (assetId == null || assetId.isBlank()) return;
        Entry prev = entries.get(assetId);
        record(new Entry(assetId, purpose != null ? purpose : (prev == null ? Purpose.UNKNOWN : prev.purpose()),
                State.DESTROYED, null, null, 0, 0, 0, nowMillis));
    }

    /** 当前状态（无记录 → null）。 */
    public Entry get(String assetId) {
        return assetId == null ? null : entries.get(assetId);
    }

    /**
     * 可恢复线索：状态 LOST 且带位置（能去拿回来）的条目。规划提示词用它。
     * 按最后见到时间倒序（最近的先）。
     */
    public List<Entry> recoverable() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.state() == State.LOST && e.hasLocation()) out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.lastSeenMillis(), a.lastSeenMillis()));
        return Collections.unmodifiableList(out);
    }

    /** 全部条目快照（不可变，按 id 排序）。 */
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort((a, b) -> a.assetId().compareTo(b.assetId()));
        return Collections.unmodifiableList(out);
    }

    public int size() {
        return entries.size();
    }

    /**
     * 哪些 assetId 值得进历史（过滤泥土/圆石这类无恢复价值的杂物）。
     * 只保留：装备/工具（_helmet/_chestplate/_leggings/_boots/_pickaxe/_axe/_shovel/_sword/_hoe/_shield）、
     * 食物、以及显式用途非 UNKNOWN 的。纯静态可单测。
     */
    public static boolean worthRemembering(String assetId, Purpose purpose) {
        if (assetId == null || assetId.isBlank()) return false;
        if (purpose != null && purpose != Purpose.UNKNOWN) return true;
        String id = assetId.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : WORTH_SUFFIXES) {
            if (id.endsWith(suffix)) return true;
        }
        for (String food : WORTH_FOODS) {
            if (id.equals(food)) return true;
        }
        return false;
    }

    private static final List<String> WORTH_SUFFIXES = List.of(
            "_helmet", "_chestplate", "_leggings", "_boots",
            "_pickaxe", "_axe", "_shovel", "_sword", "_hoe", "_shield");
    private static final List<String> WORTH_FOODS = List.of(
            "minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
            "minecraft:cooked_chicken", "minecraft:cooked_mutton", "minecraft:golden_apple",
            "minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:apple");

    // ---- JSON 往返（磁盘持久化用；坏数据安全回退空仓库） ----

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String toJson() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("assetId", e.assetId());
            o.addProperty("purpose", e.purpose().name());
            o.addProperty("state", e.state().name());
            if (e.lastCount() != null) o.addProperty("lastCount", e.lastCount());
            if (e.dimension() != null) o.addProperty("dimension", e.dimension());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            o.addProperty("lastSeenMillis", e.lastSeenMillis());
            arr.add(o);
        }
        return GSON.toJson(arr);
    }

    public static AssetHistory fromJson(String json) {
        AssetHistory h = new AssetHistory();
        if (json == null || json.isBlank()) return h;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return h;
            for (JsonElement el : root.getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "assetId");
                if (id == null || id.isBlank()) continue;
                Purpose purpose = purposeOf(str(o, "purpose"));
                State state = stateOf(str(o, "state"));
                Integer count = o.has("lastCount") && !o.get("lastCount").isJsonNull()
                        ? o.get("lastCount").getAsInt() : null;
                h.record(new Entry(id, purpose, state, count,
                        str(o, "dimension"), num(o, "x"), num(o, "y"), num(o, "z"),
                        o.has("lastSeenMillis") ? o.get("lastSeenMillis").getAsLong() : 0L));
            }
        } catch (RuntimeException ex) {
            return new AssetHistory();   // 坏文件安全回退
        }
        return h;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static int num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }

    private static Purpose purposeOf(String name) {
        try {
            return name == null ? Purpose.UNKNOWN : Purpose.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return Purpose.UNKNOWN;
        }
    }

    private static State stateOf(String name) {
        try {
            return name == null ? State.UNKNOWN : State.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return State.UNKNOWN;
        }
    }
}
