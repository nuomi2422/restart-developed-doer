package com.dwinovo.numen.rdd.core;

/**
 * P2 资产声明元字段（埋点/真相层工单 · 2026-09-25）。
 *
 * <p>所有进入规划快照的资产声明统一带两个元字段：{@code source}（声明来源）与
 * {@code verifiedAtMillis}（验证时间）。<b>不设 confidence</b>——字段先空着占位，P3 再填逻辑。
 *
 * <p>语义（在 PlanningAssetSnapshot 中）：
 * <ul>
 *   <li>{@code live_scan}：来自最近一次实时背包扫描（P2-A 唯一持有真相）。</li>
 *   <li>{@code world_registry}：来自持久化世界资产注册表（基地/结构/村庄等）。</li>
 *   <li>{@code history}：来自资产历史/恢复线索（P2.1，尚未接入 claims 聚合）。</li>
 * </ul>
 *
 * <p>注意：这是一份<b>带证言来源的读数</b>，不是永恒事实。Planner 应据此判断旧证言：
 * {@code verified_at} 越久远，越不值得盲目依赖（对应“验证成本”问题——先留元字段，
 * 置信度逻辑 P3 再做）。
 */
public record AssetClaim(String assetId, int count, String source, Long verifiedAtMillis) {

    public static final String SOURCE_LIVE_SCAN = "live_scan";
    public static final String SOURCE_WORLD_REGISTRY = "world_registry";
    public static final String SOURCE_HISTORY = "history";

    public AssetClaim {
        if (assetId == null || assetId.isBlank()) throw new IllegalArgumentException("assetId required");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source required");
        if (count < 0) throw new IllegalArgumentException("count must be >= 0");
    }
}