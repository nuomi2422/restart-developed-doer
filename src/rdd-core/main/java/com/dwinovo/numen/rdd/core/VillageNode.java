package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * P2-D 村庄节点（GPT 外脑建议 · 2026-09-25）：把 {@code world_village} 观测投影成**一等事实节点**。
 *
 * <p>原则（人工裁决）：<b>先做 VillageObservation（事实）→ 再由 Planner 评价价值 → 才形成策略</b>。
 * 本类只负责"事实"，不含任何"优先/优先度"策略；策略是后续 P2-E 的事。
 *
 * <p>节点字段：位置 / 资源摘要（村民/铁傀儡/箱子/作物/书架）/ 是否已搜过（searched）。
 * 纯 JVM、可单测，只读 {@link AssetRegistry}。
 */
public final class VillageNode {

    /** 一个村庄事实节点。 */
    public record Node(String assetId, String dimension, int x, int y, int z,
                       int villagers, int ironGolems, int chests, int crops, int bookshelves,
                       boolean searched) {

        /** 是否有可作为早期据点的实质资源（保守：有箱子 或 有村民）。 */
        public boolean hasUsableResources() {
            return chests > 0 || villagers > 0;
        }

        /** 资源摘要一行。 */
        public String resourceSummary() {
            return "villagers=" + villagers + " iron_golem=" + ironGolems
                    + " chests=" + chests + " crops=" + crops + " bookshelves=" + bookshelves;
        }

        /** 渲染给规划提示词的一行。 */
        public String render() {
            return "- village @ " + dimension + "(" + x + "," + y + "," + z + ")"
                    + " " + resourceSummary()
                    + (searched ? " [SEARCHED]" : " [NOT SEARCHED]");
        }
    }

    private VillageNode() {}

    /** 从注册表投影出全部村庄节点（按已搜过、资源多寡排序：优先实质资源且未搜）。 */
    public static List<Node> extract(AssetRegistry registry) {
        if (registry == null) return List.of();
        List<Node> out = new ArrayList<>();
        for (AssetRegistry.AssetEntry entry : registry.snapshot()) {
            if (!"world_village".equals(entry.observation().type())) continue;
            Map<String, Object> v = entry.observation().value();
            Map<String, Object> res = asMap(v.get("resources"));
            // searched：显式 searched=true，或 state=SEARCHED（旧观测用 state=SCANNED 表示"已扫描资源"，
            // 那是"观测过"不是"搜刮过"——保守起见只认显式 searched/SEARCHED）。
            boolean searched = "true".equalsIgnoreCase(String.valueOf(v.get("searched")))
                    || "SEARCHED".equals(String.valueOf(v.get("state")));
            out.add(new Node(
                    entry.assetId(),
                    str(v.get("dimension")), num(v.get("x")), num(v.get("y")), num(v.get("z")),
                    num(res.get("villagers")), num(res.get("iron_golem")),
                    num(res.get("chests")), num(res.get("crops")), num(res.get("bookshelves")),
                    searched));
        }
        out.sort(Comparator
                .comparing((Node n) -> n.searched())            // 未搜过优先
                .thenComparing(n -> !n.hasUsableResources())     // 有实质资源优先
                .thenComparing(Node::assetId));
        return List.copyOf(out);
    }

    /** 渲染成规划提示词块（空则空串）。 */
    public static String render(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<known_villages>\n");
        sb.append("Villages are fact nodes (NOT yet a strategy). Prefer using a village's existing\n");
        sb.append("bed/chest/crops over building from scratch in the wild; re-check before relying.\n");
        for (Node n : nodes) sb.append(n.render()).append('\n');
        sb.append("</known_villages>");
        return sb.toString();
    }

    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object o) {
        return o == null ? "unknown" : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
