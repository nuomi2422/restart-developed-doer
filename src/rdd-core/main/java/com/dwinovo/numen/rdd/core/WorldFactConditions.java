package com.dwinovo.numen.rdd.core;

import java.util.Map;
import java.util.Set;

/**
 * Schema only. Actual evidence is read by the Minecraft host, never by the core.
 *
 * <h2>加一个 type 必须同步四处</h2>
 * <pre>
 *   ① 本类（schema）        ② {@code RddWorldFacts.matches}（真身）        ③ {@code RddDecomposer} 提示词（告知规划器）
 *   ④ {@code WorldFactConditionsPinTest}（钉死清单，防漏）
 * </pre>
 * 漏任何一处都会得到 RL-18 同族事故：规划器生成了一个永远判不出来的条件，
 * 整条链静默卡死且不产生失败。更隐蔽的是 {@code RddDecomposer.parseOne}
 * 对未知 type 是<b>静默丢弃整条二级、连报错都没有</b>。
 *
 * <h2>为什么结构 / 群系 / 方块都允许 {@code #tag}</h2>
 * 原版注册表里「一个东西有好几个变体」是常态：村庄有五个 id、海底废墟有冷暖两版、
 * 「任意森林」是一整个 biome tag。规划器如果必须逐个枚举变体，漏一个就静默判假。
 * 精确 id 与 tag 都接受，才让「到了任意村庄」这种话能变成可判定的条件。
 */
public final class WorldFactConditions {
    private WorldFactConditions() {}

    /** 本构建认识的世界事实类型全集。加 type 时本清单必须同步改。 */
    private static final Set<String> TYPES = Set.of(
            "advancement", "structure", "entity_killed", "base",
            "biome", "block_nearby", "container_nearby",
            // A 组：原版 Stats（服务器权威、跨重启、每同伴终身）——判“真的做了 N 次”
            "block_mined", "item_crafted", "item_used", "item_picked_up",
            // B 组：位置 / 维度
            "dimension", "position_at", "y_below",
            // C 组：实体 / 精确坐标
            "entity_nearby", "block_at", "container_at");

    /** 判定半径上限。判定只读一个邻域，永不遍历世界 —— 这是它能每秒跑一次的前提。 */
    public static final int MAX_NEARBY_RADIUS = 16;
    public static final int DEFAULT_BLOCK_RADIUS = 8;
    public static final int DEFAULT_CONTAINER_RADIUS = 8;
    /** position_at 的默认容差半径（格）。 */
    public static final int DEFAULT_POSITION_RADIUS = 4;

    public static boolean valid(Map<String, Object> condition) {
        if (condition == null || condition.containsKey("asset_key") || condition.containsKey("group")) return false;
        Object type = condition.get("type");
        if (!(type instanceof String name)) return false;
        if (condition.containsKey("dimension") && !id(condition.get("dimension"))) return false;
        return switch (name) {
            case "advancement" -> id(condition.get("advancement"));
            case "structure" -> tag(condition.get("structure"));
            case "entity_killed" -> id(condition.get("entity")) && positive(condition.getOrDefault("minimum", 1));
            case "base" -> true;
            case "biome" -> tag(condition.get("biome"));
            case "block_nearby" -> tag(condition.get("block")) && radius(condition.get("radius"), MAX_NEARBY_RADIUS);
            case "container_nearby" -> tag(condition.get("item"))
                    && radius(condition.get("radius"), MAX_NEARBY_RADIUS)
                    && positive(condition.getOrDefault("minimum", 1));
            case "block_mined" -> tag(condition.get("block")) && optionalPositive(condition.get("minimum"));
            case "item_crafted", "item_used", "item_picked_up" ->
                    tag(condition.get("item")) && optionalPositive(condition.get("minimum"));
            case "dimension" -> id(condition.get("dimension"));
            case "y_below" -> integer(condition.get("y"));
            case "position_at" -> coord(condition) && radius(condition.get("radius"), MAX_NEARBY_RADIUS);
            case "entity_nearby" -> tag(condition.get("entity"))
                    && radius(condition.get("radius"), MAX_NEARBY_RADIUS)
                    && optionalPositive(condition.get("minimum"));
            case "block_at" -> coord(condition) && tag(condition.get("block"));
            case "container_at" -> coord(condition) && tag(condition.get("item"))
                    && optionalPositive(condition.get("minimum"));
            default -> false;
        };
    }

    public static boolean knownType(Object type) {
        return type instanceof String s && TYPES.contains(s);
    }

    // ── 宿主侧读取用的解析器（形状已由 valid 把关，这里不再失败，只给默认值）──────

    /** 邻域判定半径；未给或非法时回落到缺省值（合法路径上 {@link #valid} 已经挡过非法值）。 */
    public static int radiusOf(Map<String, Object> condition, int fallback) {
        Object raw = condition == null ? null : condition.get("radius");
        return raw instanceof Number n && n.intValue() >= 1 && n.intValue() <= MAX_NEARBY_RADIUS
                ? n.intValue() : fallback;
    }

    public static int minimumOf(Map<String, Object> condition, int fallback) {
        Object raw = condition == null ? null : condition.get("minimum");
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : fallback;
    }

    /** 去掉 {@code #} 前缀后的注册表 id；不是合法形状返回 null。 */
    public static String tagId(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) return null;
        String body = raw.startsWith("#") ? raw.substring(1) : raw;
        return id(body) ? body : null;
    }

    /** 该条件写的是 tag（{@code #x:y}）还是精确 id（{@code x:y}）。 */
    public static boolean isTag(Object value) {
        return value instanceof String raw && raw.startsWith("#");
    }

    private static boolean id(Object value) {
        return value instanceof String s && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private static boolean tag(Object value) {
        if (value instanceof String s && s.startsWith("#")) return id(s.length() > 1 ? s.substring(1) : null);
        return id(value);
    }

    private static boolean positive(Object value) {
        return value instanceof Number n && n.intValue() > 0 && n.doubleValue() == n.intValue();
    }

    /** 可选 minimum：缺省合法；给了必须是 >0 整数。 */
    private static boolean optionalPositive(Object value) {
        return value == null || positive(value);
    }

    /** 任意整数（可为负），用于坐标 / 高度。 */
    private static boolean integer(Object value) {
        return value instanceof Number n && n.doubleValue() == n.intValue();
    }

    /** 必须有整数 x/y/z 三元。 */
    private static boolean coord(Map<String, Object> condition) {
        return integer(condition.get("x")) && integer(condition.get("y")) && integer(condition.get("z"));
    }

    private static boolean radius(Object value, int cap) {
        if (value == null) return true;
        return value instanceof Number n
                && n.intValue() >= 1 && n.intValue() <= cap
                && n.doubleValue() == n.intValue();
    }
}