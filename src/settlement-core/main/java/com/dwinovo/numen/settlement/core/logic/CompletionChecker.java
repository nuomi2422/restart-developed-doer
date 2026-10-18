package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.accept.WorldProbe;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.LocalPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 施工收口的<b>世界侧判据</b>（纯逻辑）——"这块地到底建成了没、还差哪几格"。
 *
 * <p>为什么必须有它：施工工具执行结束 ≠ 设施已建好。缺料、被阻挡、够不到，都会让任务
 * "正常结束"而留下半成品。用户点名的要求是「任何中断后都知道这块地正在建什么、还差什么」，
 * 而任务自己的计数在任务被顶替/重启后就拿不到了——所以答案只能从<b>世界</b>里读。
 *
 * <p>判据是<b>结构标记</b>（{@link Mark}）：模板声明"蓝图局部坐标上应该有什么方块"，
 * 这里按锚点 + 旋转把它们映到世界坐标逐个核对。标记是<b>采样</b>而不是全量图纸：
 * 目的是判断"这道围栏立起来了没"，不是重放整张蓝图。
 */
public final class CompletionChecker {

    private CompletionChecker() {}

    /** 一处结构标记：蓝图局部坐标 + 该处应有的方块 id。 */
    public record Mark(int localX, int localY, int localZ, String blockId) { }

    /** 核对结果：命中几处、缺哪几处。 */
    public record Result(int matched, int total, List<String> missing) {

        public boolean complete() {
            return total > 0 && missing.isEmpty();
        }

        public int missingCount() {
            return missing.size();
        }
    }

    /**
     * 按锚点 + 旋转核对全部标记。
     *
     * @param marks   模板声明的结构标记（蓝图局部坐标）
     * @param anchor  蓝图锚点（最低角的世界坐标）
     * @param sizeX   蓝图局部 X 尺寸（旋转用）
     * @param sizeZ   蓝图局部 Z 尺寸（旋转用）
     * @param quarters 顺时针四分之一圈
     * @param probe   世界探针
     */
    public static Result check(List<Mark> marks, DimAnchor anchor, int sizeX, int sizeZ,
                               int quarters, WorldProbe probe) {
        if (marks == null || marks.isEmpty() || anchor == null || probe == null) {
            return new Result(0, 0, List.of());
        }
        List<String> missing = new ArrayList<>();
        int matched = 0;
        for (Mark m : marks) {
            RotationMath.Point2 p = RotationMath.rotateLocal(m.localX(), m.localZ(), sizeX, sizeZ, quarters);
            int wx = anchor.x() + p.x();
            int wy = anchor.y() + m.localY();
            int wz = anchor.z() + p.z();
            if (!probe.loaded(anchor.dimension(), wx, wy, wz)) {
                missing.add(world(wx, wy, wz) + "=" + shortId(m.blockId()) + "(未加载)");
                continue;
            }
            String actual = probe.blockIdAt(anchor.dimension(), wx, wy, wz);
            if (m.blockId().equals(actual)) {
                matched++;
            } else {
                missing.add(world(wx, wy, wz) + "=" + shortId(m.blockId())
                        + "(实为 " + shortId(actual) + ")");
            }
        }
        return new Result(matched, marks.size(), missing);
    }

    /** 把局部标记换算成世界坐标（供展示/调试）。 */
    public static DimAnchor toWorld(Mark m, DimAnchor anchor, int sizeX, int sizeZ, int quarters) {
        RotationMath.Point2 p = RotationMath.rotateLocal(m.localX(), m.localZ(), sizeX, sizeZ, quarters);
        return DimAnchor.of(anchor.dimension(), anchor.x() + p.x(), anchor.y() + m.localY(),
                anchor.z() + p.z());
    }

    /** 便利：从局部点造标记。 */
    public static Mark mark(LocalPoint p, int localY, String blockId) {
        return new Mark(p.x(), localY, p.z(), blockId);
    }

    private static String world(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.replace("minecraft:", "");
    }
}
