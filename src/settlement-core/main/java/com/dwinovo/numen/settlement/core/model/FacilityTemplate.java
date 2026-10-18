package com.dwinovo.numen.settlement.core.model;

import com.dwinovo.numen.settlement.core.logic.CompletionChecker;

import java.util.List;

/**
 * 一个可放置的设施模板（目录条目）——"AI 选模板、选格子"里的那个<b>模板</b>。
 *
 * <p>它是纯声明：占多大、入口在哪、要什么材料、哪些步骤还没自动化。真正的蓝图生成与施工
 * 仍由宿主侧（{@code plugins/settlement}）的生成器 + {@code blueprint action=build} 承担；
 * 本模块只做"占地/入口/材料/可用程度"的<b>声明与校验</b>，所以能纯 JVM 单测。
 *
 * <p>三条用户点名的规则体现在字段里：
 * <ol>
 *   <li><b>占地向上取整</b>：{@link #footprintCellsX}/{@link #footprintCellsZ} 是<b>网格占用</b>
 *       （5×5 为 1 格、7×7 为 2 格、9×9 为 2 格），不是蓝图尺寸；
 *       {@link #sizeX}/{@link #sizeZ} 才是实际蓝图尺寸。多出来的位置是留白/通行空间。</li>
 *   <li><b>入口带通行要求</b>：{@link #entranceLocal}（蓝图局部坐标）+ {@link #entranceFacing}
 *       声明门朝哪边、门外需要几格空；{@link #clearanceOutside} 是门外需要的净空。</li>
 *   <li><b>可用程度如实标注</b>：{@link #maturity} 把"能生成文件"和"能完整自动建成"分开。</li>
 * </ol>
 */
public record FacilityTemplate(
        String id,
        String displayName,
        FacilityKind kind,
        int sizeX,
        int sizeY,
        int sizeZ,
        int footprintCellsX,
        int footprintCellsZ,
        /**
         * 蓝图锚点 Y 相对基地 floorY 的偏移。
         *
         * <p>必须显式声明，因为现有三个生成器用的约定各不相同（实测）：
         * 牧场 y=0 是栅栏、落在脚层 → +1；核心屋 y=0 是地板 → 0；农田 y=0 是支撑泥土 → -1。
         * 靠"猜"就会把房子埋进地里或把农田架到半空。
         */
        int anchorYOffset,
        /**
         * 底座基准点（蓝图局部 XZ）：模块的"底座"贴到地基上的参考点。
         *
         * <p>用户 2026-10-10 点名：每个蓝图都要有一个底座坐标标记，说明它"站"在哪、怎么和地基合并。
         * 这是模块最底那一层（局部 {@code y=0}）压在地基顶面上的锚点；{@code place}/{@code catalog}
         * 会把它算到<b>世界坐标</b>并报出它<b>合并到的地基 Y 层</b>，让"模块 ↔ 地基"的对接
         * 是显式声明、不是靠猜的。旋转会跟着转（与入口/标记同口径）。
         */
        LocalPoint baseLocal,
        LocalPoint entranceLocal,
        String entranceFacing,
        int clearanceOutside,
        List<MaterialNeed> materials,
        Maturity maturity,
        List<String> unfinishedSteps,
        String notes,
        /**
         * 结构标记：蓝图局部坐标上"应该有什么方块"。用于施工后从<b>世界</b>核对
         * "这块地到底建成了没、还差哪几格"——任务自己的计数在任务被顶替/重启后拿不到，
         * 只有世界是可靠的答案。空表示该模板不做世界侧核对。
         */
        List<CompletionChecker.Mark> marks) {

    /** 一项材料需求。{@code consumed=false} 表示工具/容器类不必消耗（如熔炉）。 */
    public record MaterialNeed(String itemId, int count, boolean consumed) {

        public static MaterialNeed of(String itemId, int count) {
            return new MaterialNeed(itemId, count, true);
        }

        public static MaterialNeed tool(String itemId, int count) {
            return new MaterialNeed(itemId, count, false);
        }
    }

    /**
     * 模板可用程度——用户明确要求目录不要把"能生成文件"和"能完整自动建成"混为一谈。
     */
    public enum Maturity {
        /** 可生成蓝图、可施工、可验收：结构部分完全自动。 */
        READY,
        /** 结构可建，但有一道已知未自动化的工序（如灌水、引入动物）。 */
        STRUCTURE_ONLY,
        /** 可试建与续建，但已知封闭空间补全问题（如核心屋远墙）。 */
        PARTIAL,
        /** 只有设计、还不能施工。 */
        DESIGN_ONLY;

        public String label() {
            return switch (this) {
                case READY -> "可完整建造";
                case STRUCTURE_ONLY -> "仅结构可建（另有一道未自动化工序）";
                case PARTIAL -> "可试建与续建（已知补全问题）";
                case DESIGN_ONLY -> "仅设计，尚不可施工";
            };
        }
    }

    public FacilityTemplate {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("template id required");
        if (kind == null) throw new IllegalArgumentException("template kind required");
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("template size must be >= 1");
        }
        if (footprintCellsX < 1 || footprintCellsZ < 1) {
            throw new IllegalArgumentException("footprint must be >= 1 cell");
        }
        materials = materials == null ? List.of() : List.copyOf(materials);
        unfinishedSteps = unfinishedSteps == null ? List.of() : List.copyOf(unfinishedSteps);
        marks = marks == null ? List.of() : List.copyOf(marks);
        maturity = maturity == null ? Maturity.READY : maturity;
        clearanceOutside = Math.max(0, clearanceOutside);
        // 底座基准点缺省 = 底座正中（用户 2026-10-10 要的"每个蓝图都带底座坐标标记"）。
        baseLocal = baseLocal == null ? LocalPoint.of(sizeX / 2, sizeZ / 2) : baseLocal;
    }

    /** 蓝图外廓盒（局部坐标，min 角在 0,0）。 */
    public BlockBox localBox() {
        return BlockBox.of(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1);
    }

    /**
     * 占地是否"向上取整"地算对了：网格占用格数 × 每格边长 必须容得下蓝图尺寸。
     * 这是用户那条规则的机器判据，测试与目录校验都读它。
     */
    public boolean footprintFits(int cellSize) {
        return footprintCellsX * cellSize >= sizeX && footprintCellsZ * cellSize >= sizeZ;
    }

    public boolean needsWater() {
        for (String s : unfinishedSteps) {
            if (s == null) continue;
            String low = s.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("water") || low.contains("灌水") || low.contains("水孔")) return true;
        }
        return false;
    }

    /** 全部材料（含不消耗的）合计数量，用于展示"总料单"。 */
    public int totalMaterialCount() {
        int n = 0;
        for (MaterialNeed m : materials) n += m.count();
        return n;
    }
}
