package com.dwinovo.numen.rdd.api;

/** 一个前置资产要求：背包里 {@code assetKey} 至少 {@code minimum} 个（缺省 1）。 */
public record AssetRequirement(String assetKey, int minimum) {
    public AssetRequirement {
        if (assetKey == null || assetKey.isBlank()) throw new IllegalArgumentException("asset key required");
        if (minimum <= 0) minimum = 1;
    }

    public AssetRequirement(String assetKey) {
        this(assetKey, 1);
    }
}
