package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.Edge;
import com.dwinovo.numen.settlement.core.model.EdgeKey;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模块化平面的坐标数学（纯函数，无 Minecraft 类型）。
 *
 * <p>约定：
 * <ul>
 *   <li>格 (cx,cz) 的世界列范围 = {@code [originX + cx*(cellSize+gap), +cellSize-1]}（闭区间）；</li>
 *   <li>floor 层 = {@code floorY}，其上是 air（平台是自己铺的平地，不是自然地形）；</li>
 *   <li>蓝图的落位锚点 = 该格 floor 正上方 (minX, floorY+1, minZ)，朝向按设施登记旋转。</li>
 * </ul>
 *
 * <p>拆墙（合并）的接缝定义为：<b>低号格的最后一条边界列/行 → 高号格的第一条边界列/行</b>。
 * {@code gap==0} 时这是两条相邻列（两个设施各自的迎面对墙），{@code gap>0} 时包含中间的
 * 间隙列。这样不论蓝图把墙画在自己边缘还是共用一条缝，去墙都能连成一片。
 */
public final class PlatformMath {

    private PlatformMath() {}

    public static BlockBox cellBounds(PlatformPlan p, CellKey c) {
        if (!p.contains(c)) throw new IllegalArgumentException("cell outside platform: " + c);
        int x0 = p.originX() + c.cx() * p.stride();
        int x1 = x0 + p.cellSize() - 1;
        int z0 = p.originZ() + c.cz() * p.stride();
        int z1 = z0 + p.cellSize() - 1;
        return new BlockBox(x0, p.floorY(), z0, x1, p.floorY() + p.wallHeight(), z1);
    }

    /** 世界水平坐标落在哪个格；落在间隙/平台外返回空。 */
    public static Optional<CellKey> cellAt(PlatformPlan p, int x, int z) {
        int relX = x - p.originX();
        int relZ = z - p.originZ();
        if (relX < 0 || relZ < 0) return Optional.empty();
        int cx = relX / p.stride();
        int cz = relZ / p.stride();
        if (relX % p.stride() >= p.cellSize()) return Optional.empty();
        if (relZ % p.stride() >= p.cellSize()) return Optional.empty();
        CellKey c = new CellKey(cx, cz);
        return p.contains(c) ? Optional.of(c) : Optional.empty();
    }

    public static Optional<CellKey> neighbor(PlatformPlan p, CellKey c, Edge side) {
        // 先算坐标再决定要不要造 CellKey：CellKey 拒绝负索引，直接用
        // side.neighborOf(c) 会在平台边缘抛异常，而这里本该返回"没有邻居"。
        int nx = c.cx() + (side == Edge.EAST ? 1 : side == Edge.WEST ? -1 : 0);
        int nz = c.cz() + (side == Edge.SOUTH ? 1 : side == Edge.NORTH ? -1 : 0);
        if (nx < 0 || nz < 0 || nx >= p.cellsX() || nz >= p.cellsZ()) return Optional.empty();
        return Optional.of(new CellKey(nx, nz));
    }

    /** 相邻两格之间那条接缝的方块范围（floor 之上、wallHeight 高）；不相邻返回空。 */
    public static Optional<BlockBox> sharedBoundary(PlatformPlan p, CellKey a, CellKey b) {
        EdgeKey key = EdgeKey.between(a, b);
        if (key == null) return Optional.empty();
        CellKey low = key.low();
        int y0 = p.floorY() + 1;
        int y1 = p.floorY() + p.wallHeight();
        BlockBox lowBox = cellBounds(p, low);
        if (key.side() == Edge.EAST) {
            int lowMaxX = lowBox.maxX();
            int highMinX = p.originX() + (low.cx() + 1) * p.stride();
            return Optional.of(new BlockBox(lowMaxX, y0, lowBox.minZ(), highMinX, y1, lowBox.maxZ()));
        }
        // SOUTH
        int lowMaxZ = lowBox.maxZ();
        int highMinZ = p.originZ() + (low.cz() + 1) * p.stride();
        return Optional.of(new BlockBox(lowBox.minX(), y0, lowMaxZ, lowBox.maxX(), y1, highMinZ));
    }

    /** 蓝图落位锚点（该格 floor 正上方的最小角）。 */
    public static DimAnchor anchor(PlatformPlan p, CellKey c) {
        if (!p.contains(c)) throw new IllegalArgumentException("cell outside platform: " + c);
        return DimAnchor.of(p.dimension(),
                p.originX() + c.cx() * p.stride(),
                p.floorY() + 1,
                p.originZ() + c.cz() * p.stride());
    }

    /** "先做一个平"：整块平台铺地板 + 清空上方。 */
    public static List<BlockEdit> flatten(PlatformPlan p, String floorBlockId) {
        if (floorBlockId == null || floorBlockId.isBlank()) {
            throw new IllegalArgumentException("floor block id required");
        }
        List<BlockEdit> edits = new ArrayList<>();
        for (int x = p.originX(); x <= p.maxX(); x++) {
            for (int z = p.originZ(); z <= p.maxZ(); z++) {
                edits.add(BlockEdit.place(x, p.floorY(), z, floorBlockId));
                for (int y = p.floorY() + 1; y <= p.floorY() + p.wallHeight(); y++) {
                    edits.add(BlockEdit.clear(x, y, z));
                }
            }
        }
        return edits;
    }

    /** "合并去墙"：清掉两格之间接缝上的方块，保留下方地板。 */
    public static List<BlockEdit> deWall(PlatformPlan p, CellKey a, CellKey b) {
        Optional<BlockBox> boundary = sharedBoundary(p, a, b);
        if (boundary.isEmpty()) return List.of();
        BlockBox box = boundary.get();
        List<BlockEdit> edits = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    edits.add(BlockEdit.clear(x, y, z));
                }
            }
        }
        return edits;
    }
}
