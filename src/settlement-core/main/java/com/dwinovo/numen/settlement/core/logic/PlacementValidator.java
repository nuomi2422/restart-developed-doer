package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * 放置前的占地校验（纯函数）——用户点名的第 ① 条：坐标映射、旋转、重叠拒绝、越界、
 * 入口空间检查。
 *
 * <p>核心契约：<b>检查通过只代表允许开工，不保证必然完工</b>。执行中仍可能缺料、被阻挡、
 * 够不到；所以这个类只答"能不能放"，绝不假装"一定建成"。
 *
 * <p>拒绝一律<b>在改世界之前</b>发生——重复放置同一个请求必须在动土前就被挡下。
 */
public final class PlacementValidator {

    private PlacementValidator() {}

    /** 一条拒绝/警告原因。{@code fatal=true} 表示必须拒绝。 */
    public record Finding(String code, String message, boolean fatal) {

        public static Finding reject(String code, String message) {
            return new Finding(code, message, true);
        }

        public static Finding warn(String code, String message) {
            return new Finding(code, message, false);
        }
    }

    /** 校验结果。 */
    public record Result(List<Finding> findings) {

        public boolean ok() {
            for (Finding f : findings) {
                if (f.fatal()) return false;
            }
            return true;
        }

        public List<String> rejects() {
            List<String> out = new ArrayList<>();
            for (Finding f : findings) {
                if (f.fatal()) out.add(f.code() + ": " + f.message());
            }
            return out;
        }

        public List<String> warnings() {
            List<String> out = new ArrayList<>();
            for (Finding f : findings) {
                if (!f.fatal()) out.add(f.code() + ": " + f.message());
            }
            return out;
        }
    }

    /**
     * 完整校验。
     *
     * @param plan     基地基准网格
     * @param cell     目标格
     * @param template 模板
     * @param rotation 顺时针四分之一圈
     * @param registry 现有登记（重叠判定）
     * @param dimension 维度
     * @param ignoreFacilityId 续建时允许"和自己重叠"（传该设施 id；新放置传 null）
     */
    public static Result validate(PlatformPlan plan, CellKey cell, FacilityTemplate template,
                                  int rotation, FacilityRegistry registry, String dimension,
                                  String ignoreFacilityId) {
        List<Finding> findings = new ArrayList<>();

        // 1) 越界：占地格必须都在平台网格内。
        for (CellKey c : PlacementMath.occupiedCells(plan, cell, template)) {
            if (!plan.contains(c)) {
                findings.add(Finding.reject("OUT_OF_BOUNDS",
                        "模板 " + template.id() + " 占 " + template.footprintCellsX() + "×"
                                + template.footprintCellsZ() + " 格，从 " + cellName(cell)
                                + " 起会越出基地网格（" + plan.cellsX() + "×" + plan.cellsZ() + "）；"
                                + "换一个更靠西北的格子或扩大基地。"));
                break;
            }
        }
        if (!findings.isEmpty()) {
            return new Result(findings);
        }

        PlacementMath.PlacementPlan placement = PlacementMath.resolve(plan, cell, template, rotation);

        // 2) 占地是否"向上取整"算对（模板自身声明的一致性）。
        if (!template.footprintFits(plan.cellSize())) {
            findings.add(Finding.reject("FOOTPRINT_TOO_SMALL",
                    "模板 " + template.id() + " 声明占 " + template.footprintCellsX() + "×"
                            + template.footprintCellsZ() + " 格，容不下 " + template.sizeX() + "×"
                            + template.sizeZ() + " 的蓝图（每格 " + plan.cellSize() + "）。"));
        }

        // 3) 重叠拒绝：和已有设施的占地盒相交就拒绝（除非是它自己在续建）。
        // ★ 例外：一级地皮（platform_*）是"地面覆盖"，不是要抢格子的建筑——
        //   用户 V2 流程就是"先修 14×14 圆石地皮，再往上面放 7×7 模块"。
        //   地皮登记的意义是施工账与地图可见，不是圈地；所以它与建筑模块互相放行，
        //   但地皮之间、模块之间照旧互斥。
        BlockBox foot = placement.footprintBox();
        boolean placingPlatform = template.id().startsWith("platform");
        for (FacilityRecord f : registry == null ? List.<FacilityRecord>of() : registry.inDimension(dimension)) {
            if (ignoreFacilityId != null && ignoreFacilityId.equals(f.id())) {
                continue;
            }
            boolean existingPlatform = f.construction().template() != null
                    && f.construction().template().startsWith("platform");
            if (placingPlatform != existingPlatform) {
                continue;   // 地皮 vs 模块：互相放行
            }
            if (foot.intersects(f.bounds())) {
                findings.add(Finding.reject("OVERLAP",
                        "占地 " + box(foot) + " 与已有设施 '" + f.id() + "'（" + f.kind().name()
                                + " " + box(f.bounds()) + "）重叠；换格子，或先 settlement_unregister 它。"));
            }
        }

        // 4) 入口空间检查：门外净空不能被已有设施的结构区占住。
        //    ★ 同样豁免一级地皮（platform_*）：模块就放在地皮上，门外净空必然在地皮范围内。
        BlockBox clearance = PlacementMath.clearanceBox(placement, template);
        for (FacilityRecord f : registry == null ? List.<FacilityRecord>of() : registry.inDimension(dimension)) {
            if (ignoreFacilityId != null && ignoreFacilityId.equals(f.id())) {
                continue;
            }
            boolean existingPlatform = f.construction().template() != null
                    && f.construction().template().startsWith("platform");
            if (placingPlatform != existingPlatform) {
                continue;   // 地皮 vs 模块：互相放行
            }
            if (clearance.intersects(f.bounds())) {
                findings.add(Finding.reject("ENTRANCE_BLOCKED",
                        "入口朝 " + template.entranceFacing() + "，门外净空 " + box(clearance)
                                + " 被设施 '" + f.id() + "' 占住；换个朝向（rotation）或换格子。"));
            }
        }

        // 5) 占地格与已有设施的"格占用"对不上（同格不同盒的兜底，防重叠判定漏网）。
        //    同样豁免地皮（见上）：模块要放在一级地皮上。
        //    ★ 2026-10-09 晚修：<b>现算格，不信 FacilityRecord.cells 快照</b>。
        //    `cells` 是放置当时按"当时的基地基准"记下的，基地基准改过（原点/每格边长变了）之后
        //    就陈旧了 —— 实测把 core_house_d3 记成 D3/D4/E3/E4（它真实 bounds 只落在 B/C 列），
        //    于是 `place pen_sheep cell=E4` 被这条陈旧记录误报 CELL_TAKEN。
        //    改从 `f.bounds()`（世界坐标是固定的，不会漂）按当前 plan 现算占用格，与
        //    overview 的 `cellsOf` 同口径。
        for (FacilityRecord f : registry == null ? List.<FacilityRecord>of() : registry.inDimension(dimension)) {
            if (ignoreFacilityId != null && ignoreFacilityId.equals(f.id())) {
                continue;
            }
            boolean existingPlatform = f.construction().template() != null
                    && f.construction().template().startsWith("platform");
            if (placingPlatform != existingPlatform) {
                continue;
            }
            for (CellKey c : cellsCovering(plan, f.bounds())) {
                if (placement.cells().contains(c)) {
                    findings.add(Finding.reject("CELL_TAKEN",
                            "格 " + cellName(c) + " 已被设施 '" + f.id() + "' 登记占用。"));
                    break;
                }
            }
        }

        // 6) 警告：门外净空伸出基地网格外（门朝基地外面）。不致命，但必须说清——
        //    "门朝向外面"和"门被堵住"是两回事，不能让调用方自己去猜。
        BlockBox out = PlacementMath.clearanceBox(placement, template);
        if (out.maxX() > plan.maxX() || out.minX() < plan.originX()
                || out.maxZ() > plan.maxZ() || out.minZ() < plan.originZ()) {
            findings.add(Finding.warn("ENTRANCE_OUTSIDE_PLATFORM",
                    "入口朝 " + template.entranceFacing() + "，门外净空 " + box(out)
                            + " 伸出基地网格；门会朝向基地外面（可接受，但确认这是你要的）。"));
        }

        return new Result(findings);
    }

    /** 便利入口：不忽略任何设施。 */
    public static Result validate(PlatformPlan plan, CellKey cell, FacilityTemplate template,
                                  int rotation, FacilityRegistry registry, String dimension) {
        return validate(plan, cell, template, rotation, registry, dimension, null);
    }

    public static String cellName(CellKey c) {
        return String.valueOf((char) ('A' + c.cx())) + (c.cz() + 1);
    }

    /** 一个世界盒按当前基地网格覆盖到哪些格（x/z 投影；越出网格的点跳过）。 */
    private static List<CellKey> cellsCovering(PlatformPlan plan, BlockBox box) {
        List<CellKey> out = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                PlatformMath.cellAt(plan, x, z).ifPresent(c -> {
                    if (!out.contains(c)) out.add(c);
                });
            }
        }
        return out;
    }

    private static String box(BlockBox b) {
        return b.minX() + "," + b.minZ() + ".." + b.maxX() + "," + b.maxZ();
    }
}
