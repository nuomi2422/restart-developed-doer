package com.dwinovo.numen.settlement.core.model;

/**
 * 蓝图局部平面坐标（不含 Y）。
 *
 * <p>用来声明"门在蓝图的哪一格""门外站位在哪"。允许<b>越界</b>（如 z=-1 表示盒子北侧一格），
 * 因为门外站位本来就在蓝图外廓之外——这正是要显式声明它、而不是靠猜的原因。
 *
 * <p>局部坐标系：{@code x∈[0,sizeX)}、{@code z∈[0,sizeZ)}，min 角在 (0,0)。
 */
public record LocalPoint(int x, int z) {

    public static LocalPoint of(int x, int z) {
        return new LocalPoint(x, z);
    }
}
