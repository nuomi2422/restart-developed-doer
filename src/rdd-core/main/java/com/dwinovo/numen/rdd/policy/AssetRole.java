package com.dwinovo.numen.rdd.policy;

/**
 * 资产用途（P2.1）：同一实物（如 diamond_chestplate）在规划里的“角色”。
 * 事实（AssetEntry：有 3 件）与用途分离——用途是规划语义，存 {@link AssetPurposeStore}，不污染资产事实层。
 */
public enum AssetRole {
    /** 当前战斗/在穿一套。 */
    COMBAT,
    /** 死亡恢复备用套（死了能回去换）。 */
    DEATH_RECOVERY,
    /** 基地/据点设施（床、箱子、熔炉、工作台等）。 */
    BASE,
    /** 通用工具（镐/斧/铲/剑等）。 */
    UTILITY,
    /** 药水/抗火/治疗等生存药。 */
    MEDICINE,
    /** 食物储备。 */
    FOOD_RESERVE
}
