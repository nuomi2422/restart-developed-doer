package com.dwinovo.numen.rdd.fail;

/**
 * 诊断上下文（P2.0）：判定出口所需的客观事实，由宿主在真实世界核实后填入（不许臆测）。
 *
 * @param hasBackupEquipment 是否存在死亡恢复用途的备用装备（由 AssetPurpose 判定）
 * @param hasBaseAndCoords   是否有基地且知道坐标（可回去补给/回收）
 * @param repeatedFailure    同一子步是否已重复失败（次数达阈值）
 * @param targetUnrecoverable 目标是否已永久失去（基地毁/入口丢/关键资源不可得）
 */
public record FailureContext(boolean hasBackupEquipment, boolean hasBaseAndCoords,
                             boolean repeatedFailure, boolean targetUnrecoverable) {
}
