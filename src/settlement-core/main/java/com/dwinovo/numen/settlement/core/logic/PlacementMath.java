package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.LocalPoint;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 「选格放置」的坐标数学（纯函数）：把 {@code (格, 模板, 旋转)} 换算成世界坐标。
 *
 * <p>AI 只说"在 B2 放一个牧场"，本类负责算出蓝图锚点、实际占地盒、入口内外站位、
 * 以及占用哪些格。<b>原点不随同伴当前位置漂移</b>——一切以已登记的基地基准为准，
 * 这正是用户点名的要求。
 *
 * <p>对齐约定（V1，确定性优先）：蓝图 min 角与<b>占地格的最小角</b>对齐。
 * 蓝图比占地小时多出来的是留白/通行空间（如 7×7 蓝图落在 2×2 格 = 10×10 占地里，
 * 右侧/南侧各留 3 格）。
 */
public final class PlacementMath {

    private PlacementMath() {}

    /** 一次放置的完整坐标解算结果。 */
    public record PlacementPlan(
            String templateId,
            CellKey cell,
            int rotationQuarters,
            /** 占地格覆盖的世界 XZ 盒（floor 层，高 1 格）。 */
            BlockBox footprintBox,
            /** 旋转后蓝图外廓的世界盒。 */
            BlockBox structureBox,
            /** 蓝图锚点（最低角，喂给 {@code blueprint action=build}）。 */
            DimAnchor anchor,
            /** 入口内侧站位。 */
            DimAnchor entranceInside,
            /** 入口外侧站位。 */
            DimAnchor entranceOutside,
            /** 占用的网格格。 */
            List<CellKey> cells) { }

    /**
     * 占地格覆盖的世界盒。
     *
     * <p>★ Y 起点必须带上 {@link FacilityTemplate#anchorYOffset()}：各生成器的蓝图 y=0
     * 落在不同层（牧场/核心屋在 floorY，农田/平台在 floorY-1）。2026-10-09 实机抓到：
     * 农田登记成 y=68..69，而它真正的支撑泥土层在 <b>y=67</b>——保护区与验收带都漏掉了
     * 一层，等于把设施最底下那层留在保护之外。
     */
    public static BlockBox footprintBox(PlatformPlan plan, CellKey cell, FacilityTemplate template) {
        int x0 = plan.originX() + cell.cx() * plan.stride();
        int z0 = plan.originZ() + cell.cz() * plan.stride();
        int x1 = x0 + template.footprintCellsX() * plan.cellSize() - 1;
        int z1 = z0 + template.footprintCellsZ() * plan.cellSize() - 1;
        int y0 = plan.floorY() + template.anchorYOffset();
        return BlockBox.of(x0, y0, z0, x1, y0 + template.sizeY() - 1, z1);
    }

    /** 占用哪些格（从 cell 起向东南铺 footprintCells）。 */
    public static List<CellKey> occupiedCells(PlatformPlan plan, CellKey cell, FacilityTemplate template) {
        List<CellKey> out = new ArrayList<>();
        for (int dx = 0; dx < template.footprintCellsX(); dx++) {
            for (int dz = 0; dz < template.footprintCellsZ(); dz++) {
                out.add(CellKey.of(cell.cx() + dx, cell.cz() + dz));
            }
        }
        return out;
    }

    /**
     * 解算一次放置。不做任何合法性判断（那是 {@link PlacementValidator} 的事）——
     * 这里只负责"算出坐标"，两边分开才能把"算错"和"不许放"分清。
     */
    public static PlacementPlan resolve(PlatformPlan plan, CellKey cell, FacilityTemplate template,
                                        int rotationQuarters) {
        int q = RotationMath.normalizeQuarters(rotationQuarters);
        BlockBox footprint = footprintBox(plan, cell, template);
        int ax = footprint.minX();
        // 蓝图锚点 Y：各生成器约定不同（栅栏在脚层 / 地板在脚层 / 农田支撑在脚下一层），
        // 由模板显式声明 anchorYOffset，不靠猜。
        int ay = plan.floorY() + template.anchorYOffset();
        int az = footprint.minZ();

        int rotSizeX = RotationMath.rotatedSizeX(template.sizeX(), template.sizeZ(), q);
        int rotSizeZ = RotationMath.rotatedSizeZ(template.sizeX(), template.sizeZ(), q);
        BlockBox structure = BlockBox.of(ax, ay, az,
                ax + rotSizeX - 1, ay + template.sizeY() - 1, az + rotSizeZ - 1);

        LocalPoint in = template.entranceLocal();
        LocalPoint out = outsidePoint(in, template.entranceFacing());
        RotationMath.Point2 rin = RotationMath.rotateLocal(in.x(), in.z(),
                template.sizeX(), template.sizeZ(), q);
        RotationMath.Point2 rout = RotationMath.rotateLocal(out.x(), out.z(),
                template.sizeX(), template.sizeZ(), q);

        DimAnchor entranceInside = DimAnchor.of(plan.dimension(), ax + rin.x(), ay, az + rin.z());
        DimAnchor entranceOutside = DimAnchor.of(plan.dimension(), ax + rout.x(), ay, az + rout.z());

        return new PlacementPlan(template.id(), cell, q, footprint, structure,
                DimAnchor.of(plan.dimension(), ax, ay, az),
                entranceInside, entranceOutside, occupiedCells(plan, cell, template));
    }

    /**
     * 底座基准点的<b>世界坐标</b>：锚点 + 旋转后的 {@link FacilityTemplate#baseLocal()}，
     * Y 取模块最底层（= 蓝图锚点 Y）。
     *
     * <p>用户 2026-10-10 点名「每个蓝图都要有一个底座坐标标记，说明它站哪、怎么和地基合并」——
     * 这个点就是模块"底座"贴到地基顶面的锚点。它<b>合并到的地基 Y 层</b> = 该点 Y 的下一层
     * （{@code basePoint.y() - 1}），调用方据此告诉 AI "模块落在这、坐在 y=.. 的地基上"。
     */
    public static DimAnchor basePoint(PlacementPlan plan, FacilityTemplate template) {
        LocalPoint b = template.baseLocal();
        RotationMath.Point2 r = RotationMath.rotateLocal(b.x(), b.z(),
                template.sizeX(), template.sizeZ(), plan.rotationQuarters());
        return DimAnchor.of(plan.anchor().dimension(),
                plan.anchor().x() + r.x(), plan.anchor().y(), plan.anchor().z() + r.z());
    }

    /** 门外站位 = 入口格沿朝向再走一格（局部坐标；可能越界，这是允许的）。 */
    public static LocalPoint outsidePoint(LocalPoint entrance, String facing) {
        String f = facing == null ? "south" : facing.toLowerCase(java.util.Locale.ROOT);
        return switch (f) {
            case "north" -> LocalPoint.of(entrance.x(), entrance.z() - 1);
            case "south" -> LocalPoint.of(entrance.x(), entrance.z() + 1);
            case "east" -> LocalPoint.of(entrance.x() + 1, entrance.z());
            case "west" -> LocalPoint.of(entrance.x() - 1, entrance.z());
            default -> LocalPoint.of(entrance.x(), entrance.z() + 1);
        };
    }

    /**
     * 门外净空条（沿<b>旋转后</b>的朝向从门外站位再往外 clearance 格），用于检查是否被别的设施堵住。
     *
     * <p>★ 方向必须从<b>已旋转的</b>入口内/外站位之差推出来，不能拿模板声明的
     * {@code entranceFacing} 原文去算：那个朝向是<b>旋转前</b>的。2026-10-09 发现——
     * 旋转 90° 后门在东侧，而净空条仍朝北延伸，于是"入口被堵"判在错的一侧
     * （漏报真堵、误报假堵）。
     */
    public static BlockBox clearanceBox(PlacementMath.PlacementPlan plan, FacilityTemplate template) {
        DimAnchor in = plan.entranceInside();
        DimAnchor out = plan.entranceOutside();
        int n = Math.max(1, template.clearanceOutside());
        // 旋转保长：门外站位与门内站位相差恰好一步，取符号即为旋转后的朝向。
        int dx = Integer.signum(out.x() - in.x());
        int dz = Integer.signum(out.z() - in.z());
        if (dx == 0 && dz == 0) {
            dz = 1;   // 兜底：朝向信息缺失时按南（与 outsidePoint 的默认一致）
        }
        int x1 = out.x() + dx * (n - 1);
        int z1 = out.z() + dz * (n - 1);
        return BlockBox.of(out.x(), out.y(), out.z(), x1, out.y(), z1);
    }
}
