package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P1.5 唯一规划资产口径（Planner 提示词 + PlanGuard 共用）。
 *
 * <p>背景：以前规划读"最近一次背包扫描缓存"(1s)，依赖门读 {@link AssetRegistry}（5s），两口径不一致；
 * 死亡掉装备后缓存还会残留旧装备。这里合成一个快照。
 *
 * <p><b>口径（2026-09-25 修正）</b>：<b>实时扫描（cachedInventory）是"当前持有"的唯一真相</b>。
 * 背包资产不持久化——昨天的钻石剑不代表今天还有。因此注册表里的 {@code inventory_scan} 条目
 * <b>只作为提示</b>（lost / unknown），<b>绝不参与 available 的加减</b>：
 * <ul>
 *   <li>available 完全来自实时扫描（{@code cachedInventory}）；</li>
 *   <li>注册表 {@code inventory_scan} 的 INVALID / UNKNOWN 记入 lost / unknown，仅供提示词显式告知，
 *       <b>不</b>从 available 移除、也不覆盖其计数（死亡失效是过时判定，实时扫描才是真相）；</li>
 *   <li>注册表 {@code world_} 世界资产不参与"背包持有"计数（另由 world 资产渲染块提供）。</li>
 * </ul>
 * 注册表为空（尚未扫描）时退化为实时扫描，不会把"尚未观测"误判成"两手空空"。
 * 纯 JVM、可单测。
 */
public final class PlanningAssetSnapshot {

    private final Map<String, Integer> available;
    private final List<String> lost;
    private final List<String> unknown;
    /** 最近一次实时扫描的系统毫秒时间（可为 null = 未观测到扫描时刻），随 claims() 暴露。 */
    private final Long scanAtMillis;

    private PlanningAssetSnapshot(Map<String, Integer> available, List<String> lost, List<String> unknown, Long scanAtMillis) {
        this.available = Collections.unmodifiableMap(new TreeMap<>(available));
        this.lost = List.copyOf(lost);
        this.unknown = List.copyOf(unknown);
        this.scanAtMillis = scanAtMillis;
    }

    /** 合成快照：cachedInventory（实时扫描）= 唯一持有真相；registry 的 inventory_scan 仅作 lost/unknown 提示。 */
    public static PlanningAssetSnapshot from(Map<String, Integer> cachedInventory, AssetRegistry registry) {
        return from(cachedInventory, null, registry);
    }

    /** 同 {@link #from(Map, AssetRegistry)}，额外携带最近一次扫描时刻（供 claims 的 verified_at 用）。 */
    public static PlanningAssetSnapshot from(Map<String, Integer> cachedInventory, Long scanAtMillis, AssetRegistry registry) {
        Map<String, Integer> available = new TreeMap<>();
        if (cachedInventory != null) {
            for (Map.Entry<String, Integer> e : cachedInventory.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() >= 0) {
                    available.put(e.getKey(), e.getValue());
                }
            }
        }
        List<String> lost = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        if (registry != null) {
            for (AssetRegistry.AssetEntry entry : registry.snapshot()) {
                if (!"inventory_scan".equals(entry.observation().type())) {
                    continue;
                }
                String id = entry.assetId();
                switch (entry.status()) {
                    case OBSERVED -> {
                        // 提示语义：注册表观测不再覆盖/新增 available（实时扫描才是真相）。
                        // 什么都不做——available 已由 cachedInventory 决定。
                    }
                    case INVALID -> lost.add(id);
                    case UNKNOWN -> unknown.add(id);
                }
            }
        }
        Collections.sort(lost);
        Collections.sort(unknown);
        return new PlanningAssetSnapshot(available, lost, unknown, scanAtMillis);
    }

    /** 规划可用背包计数（不可变、按 key 排序）。 */
    public Map<String, Integer> availableCounts() {
        return available;
    }

    /**
     * 持背资产声明（带元字段）：每条 = {@code live_scan} 来源 + 最近一次扫描时刻（可为 null）。
     * <b>只覆盖实时持有的背包资产</b>；世界资产声明见 {@link #worldClaims}。
     */
    public Map<String, AssetClaim> claims() {
        Map<String, AssetClaim> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : available.entrySet()) {
            out.put(e.getKey(), new AssetClaim(e.getKey(), e.getValue(), AssetClaim.SOURCE_LIVE_SCAN, scanAtMillis));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 世界资产声明（注册表持久化的 world_* 条目，OBSERVED 才出证言）：
     * 每条 = {@code world_registry} 来源 + 该观测发生时刻（observedAt）。
     * count 语义为"在场"（presence=1），具体内容见观测 record 的 value。
     */
    public static Map<String, AssetClaim> worldClaims(AssetRegistry registry) {
        if (registry == null) return Collections.emptyMap();
        java.util.Map<String, AssetClaim> out = new java.util.LinkedHashMap<>();
        for (AssetRegistry.AssetEntry entry : registry.snapshot()) {
            if (entry.status() != AssetStatus.OBSERVED) continue;
            String type = entry.observation().type();
            if (type != null && type.startsWith("world_")) {
                out.put(entry.assetId(), new AssetClaim(entry.assetId(), 1, AssetClaim.SOURCE_WORLD_REGISTRY, entry.observation().observedAt()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 已被判定失去（INVALID）的背包资产 id（排序、不可变）。 */
    public List<String> lostIds() {
        return lost;
    }

    /** 状态不确定（UNKNOWN）的背包资产 id（排序、不可变）。 */
    public List<String> unknownIds() {
        return unknown;
    }
}
