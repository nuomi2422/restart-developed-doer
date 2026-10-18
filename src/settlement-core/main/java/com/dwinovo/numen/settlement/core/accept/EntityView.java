package com.dwinovo.numen.settlement.core.accept;

/** 只读的世界实体投影（验收用）。坐标是世界坐标。 */
public record EntityView(String typeId, double x, double y, double z) {
}
