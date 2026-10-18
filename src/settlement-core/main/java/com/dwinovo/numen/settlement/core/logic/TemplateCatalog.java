package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.FacilityKind;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate.MaterialNeed;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate.Maturity;
import com.dwinovo.numen.settlement.core.model.LocalPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 可放置模板的目录（纯声明，单一真源）。
 *
 * <p>★ V2（用户 2026-10-09 定）：<b>所有建筑模块统一 7×7、各占一格（cellSize=7），互不重合</b>；
 * 地皮一次修 <b>14×14 圆石打底</b>（= 2×2 格），"这是一级地皮，更加方便管理"。
 * 第一批：核心屋、农田、牛圈、羊圈、交易所（交易所＝两格高围墙＋放满床＋栅栏门，
 * 村民夜间自己找床睡）。
 *
 * <p>目录<b>如实标注可用程度</b>——"能生成文件"不等于"能完整自动建成"：
 * 核心屋有封闭空间补全疑问（{@code PARTIAL}，待重测），农田有灌水未自动化（{@code STRUCTURE_ONLY}）。
 *
 * <p><b>入口不用地毯</b>：2026-10-09 实机证实"栅栏+地毯"组合对我们的假玩家不可站
 * （TP 上去会落回栅栏层，没有可站面），所以默认入口是<b>栅栏门</b>（可开可关、人畜都按门走）。
 */
public final class TemplateCatalog {

    /** 每格默认边长（V2：一格 = 一个 7×7 模块）。 */
    public static final int DEFAULT_CELL_SIZE = 7;

    private static final Map<String, FacilityTemplate> BY_ID = new LinkedHashMap<>();

    static {
        // ── V2 统一 7×7 模块（各占 1 格，cellSize=7） ──────────────────

        // 羊圈：7×7 栅栏圈（24 格周长，中间换门 → 23 栅栏 + 1 门），门朝北。
        put(new FacilityTemplate(
                "pen_sheep", "羊圈（7×7）", FacilityKind.PASTURE_SHEEP,
                7, 2, 7,
                1, 1,
                0,
                LocalPoint.of(3, 3),
                LocalPoint.of(3, 0), "north", 1,
                List.of(MaterialNeed.of("minecraft:oak_fence", 23),
                        MaterialNeed.of("minecraft:oak_fence_gate", 1)),
                Maturity.READY,
                List.of(),
                "栅栏围整圈 + 北侧中间一道栅栏门；引羊用 lure_animals_into_pen。",
                penMarks("minecraft:oak_fence")));

        // 牛圈：同羊圈（animal=cow 只影响蓝图命名）。
        put(new FacilityTemplate(
                "pen_cow", "牛圈（7×7）", FacilityKind.PASTURE_COW,
                7, 2, 7,
                1, 1,
                0,
                LocalPoint.of(3, 3),
                LocalPoint.of(3, 0), "north", 1,
                List.of(MaterialNeed.of("minecraft:oak_fence", 23),
                        MaterialNeed.of("minecraft:oak_fence_gate", 1)),
                Maturity.READY,
                List.of("引入牛没有现成 AC（引羊 AC 按 species 参数可扩）"),
                "与羊圈同形；牛由人牵引或刷怪塔自然进圈。",
                penMarks("minecraft:oak_fence")));

        // 核心屋：7×7 火柴盒（地板/墙/平顶/门/床箱台炉）。
        put(new FacilityTemplate(
                "core_house", "核心屋（7×7）", FacilityKind.HOUSE,
                7, 4, 7,
                1, 1,
                0,
                LocalPoint.of(3, 3),
                LocalPoint.of(3, 0), "north", 1,
                List.of(MaterialNeed.of("minecraft:oak_planks", 144),
                        MaterialNeed.of("minecraft:oak_door", 1),
                        MaterialNeed.of("minecraft:crafting_table", 1),
                        MaterialNeed.of("minecraft:furnace", 1),
                        MaterialNeed.of("minecraft:chest", 1),
                        MaterialNeed.of("minecraft:white_bed", 1)),
                Maturity.PARTIAL,
                List.of("封闭空间内从里往外建会够不到远侧格（旧实测远墙 9 格 would not stay put；"
                        + "2026-10-09 D3 一次未复现，疑与缺料而非可达性有关——待干净空地重测）"),
                "地板/墙/平顶/门/床箱台炉一次生成。",
                houseMarks()));

        // 农田：7×7 耕地 + 中心水孔（V2 从 9×9 缩到 7×7，一模块一格）。
        put(new FacilityTemplate(
                "farm_basic", "农田（7×7）", FacilityKind.FARM,
                7, 2, 7,
                1, 1,
                -1,
                LocalPoint.of(3, 3),
                LocalPoint.of(3, 6), "south", 1,
                // 7×7 = 49 支撑泥土 + 48 格耕地（耕地按泥土记账，core overrideItem）= 97。
                List.of(MaterialNeed.of("minecraft:dirt", 97)),
                Maturity.STRUCTURE_ONLY,
                List.of("中心水孔要另用桶灌水（蓝图跳过液体格）；未灌水耕地会退化（实测）"),
                "7×7 耕地 + 中心水孔；水必须另灌，所以未灌水前不能标成生产就绪。",
                farmMarks7()));

        // 交易所：两格高栅栏围墙 + 北侧栅栏门 + 内部四张床（村民夜间自投床，封起来即可）。
        put(new FacilityTemplate(
                "trade_post", "交易所（7×7 高栏+床）", FacilityKind.TRADE,
                7, 2, 7,
                1, 1,
                0,
                LocalPoint.of(3, 3),
                LocalPoint.of(3, 0), "north", 1,
                List.of(MaterialNeed.of("minecraft:oak_fence", 46),
                        MaterialNeed.of("minecraft:oak_fence_gate", 1),
                        MaterialNeed.of("minecraft:white_bed", 4)),
                Maturity.READY,
                List.of("村民需自行走入（门先开后关）；交易站位/工作站绑定未验"),
                "围墙两层高（48 环格 − 门格 − 门上一格 = 46 栅栏 + 1 门），内部 4 张床排两排。"
                        + "村民到晚上若周围有床会自己走过来睡（用户 2026-10-09 定的做法）。",
                tradeMarks()));

        // ── 一级地皮：14×14 圆石打底（= 2×2 格），一次修好（588 格 << 32768 上限） ──
        put(new FacilityTemplate(
                "platform_cobble14", "一级地皮（14×14 圆石）", FacilityKind.GENERIC,
                14, 17, 14,
                2, 2,
                -6,
                LocalPoint.of(7, 7),
                LocalPoint.of(7, 13), "south", 1,
                // depth=6 打底的上界料单：14×14 顶面 = 196 圆石（下方填/上方清是地形相关的，实际看现场）。
                List.of(MaterialNeed.of("minecraft:cobblestone", 196)),
                Maturity.READY,
                List.of("四周若落差 >1 格需另修阶梯接地面（放模块前用 build 在四边各垫 1-2 级台阶）"),
                "用户点名的一级地皮：14×14 圆石打底，东南西北先验证能落脚，落差大就修阶梯。"
                        + "蓝图上限 32768 格，本皮 588 格一次能修，无需分批。",
                platformMarks14()));
    }

    private TemplateCatalog() {}

    // ── 结构标记（世界侧核对用） ─────────────────────────────────────
    //
    // 采样而不是全量图纸：目的是判"这道围栏立起来了没 / 墙在不在"，不是重放整张蓝图。
    // 每个模板都取四角 + 门（最容易漏的地方），因为这些正是"建了一半"的典型缺口。

    private static final String FENCE = "minecraft:oak_fence";
    private static final String GATE = "minecraft:oak_fence_gate";

    /** 7×7 牧场圈（羊/牛同形）：四角 + 门 + 每边中点栅栏。局部坐标对 7×7。 */
    private static List<CompletionChecker.Mark> penMarks(String fenceId) {
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            marks.add(new CompletionChecker.Mark(c[0], 0, c[1], fenceId));
        }
        marks.add(new CompletionChecker.Mark(3, 0, 0, GATE));    // 北中门
        marks.add(new CompletionChecker.Mark(3, 0, 6, fenceId)); // 南中
        marks.add(new CompletionChecker.Mark(0, 0, 3, fenceId)); // 西中
        marks.add(new CompletionChecker.Mark(6, 0, 3, fenceId)); // 东中
        return marks;
    }

    /** 7×7 核心屋：四角墙 + 门 + 屋顶中心 + 地板中心 + <b>四件家具</b>。 */
    private static List<CompletionChecker.Mark> houseMarks() {
        String planks = "minecraft:oak_planks";
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            marks.add(new CompletionChecker.Mark(c[0], 1, c[1], planks));
            marks.add(new CompletionChecker.Mark(c[0], 2, c[1], planks));
        }
        marks.add(new CompletionChecker.Mark(3, 1, 0, "minecraft:oak_door"));   // 南中门
        marks.add(new CompletionChecker.Mark(3, 3, 3, planks));                 // 平顶中心
        marks.add(new CompletionChecker.Mark(3, 0, 3, planks));                 // 地板中心
        // ★ 家具必须进标记：2026-10-09 实测 D3 核心屋报了「世界核对 11/11 COMPLETE」，
        //   而箱子其实<b>根本没落地</b>（背包里那件料也消耗了）——因为标记只采了结构，
        //   家具不在采样里，缺失就判不出来。用户要的是「还差什么」，家具漏了就是谎报完成。
        marks.add(new CompletionChecker.Mark(1, 1, 1, "minecraft:crafting_table"));
        marks.add(new CompletionChecker.Mark(5, 1, 1, "minecraft:furnace"));
        marks.add(new CompletionChecker.Mark(1, 1, 5, "minecraft:chest"));
        marks.add(new CompletionChecker.Mark(5, 1, 5, "minecraft:white_bed"));
        return marks;
    }

    /** 7×7 农田：四角与四边中点应是支撑泥土（y=0 层），中心水孔另验。 */
    private static List<CompletionChecker.Mark> farmMarks7() {
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}, {3, 0}, {0, 3}, {6, 3}, {3, 6}}) {
            marks.add(new CompletionChecker.Mark(c[0], 0, c[1], "minecraft:dirt"));
        }
        return marks;
    }

    /**
     * 交易所：两层围墙四角 + 门 + 四张床。
     * ★ 床必须进标记——2026-10-09 的谎报教训：采样漏掉人真正在乎的东西（家具/床），
     *   缺失就判不出来。交易所的全部意义就是床，床漏了就是这座设施没建成。
     */
    private static List<CompletionChecker.Mark> tradeMarks() {
        String planks = "minecraft:oak_planks";
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) {
            marks.add(new CompletionChecker.Mark(c[0], 0, c[1], FENCE));
            marks.add(new CompletionChecker.Mark(c[0], 1, c[1], FENCE));   // 两层高
        }
        marks.add(new CompletionChecker.Mark(3, 0, 0, GATE));              // 北中门（下层）
        // 床取【脚】位（z=3 与 z=5 两排，头朝北分别落在 z=2/z=4，互不冲突）
        marks.add(new CompletionChecker.Mark(2, 0, 3, "minecraft:white_bed"));
        marks.add(new CompletionChecker.Mark(4, 0, 3, "minecraft:white_bed"));
        marks.add(new CompletionChecker.Mark(2, 0, 5, "minecraft:white_bed"));
        marks.add(new CompletionChecker.Mark(4, 0, 5, "minecraft:white_bed"));
        return marks;
    }

    /**
     * 一级地皮（14×14 圆石）：顶面采样点应是圆石。
     *
     * <p>★ 为什么平台必须进标记（2026-10-09 晚修）：平台原来是 {@code marks=[]}，而
     * {@code inspect} 的判据写的是"模板没有标记就跳过世界核对"——于是地皮施工账永远停在
     * {@code BUILDING 0/439}，明明表面多是泥土也判不出来。用户点名"给平台加顶面采样点是圆石"。
     *
     * <p>取四角 + 四边中点 + 中心（这些正是"建了一半"最先缺的地方）。局部 Y=6：
     * 平台的蓝图锚点 Y = floorY-6（{@code anchorYOffset=-6}，depth=6），y=0..5 是往下填实层、
     * <b>y=6 才是统一铺的圆石顶面</b>，核对时映射回世界 floorY。
     *
     * <p>边界：模块压在地皮上时会把落在模块占地里的顶面换成模块方块（栅栏/地板等），
     * 此时该点核对会报"缺"——这是<b>如实</b>的（那格圆石确实被替换了）。地皮的收口核对
     * 用在"铺完地皮、还没压模块"时；压了模块后应看模块自己的账。
     */
    private static List<CompletionChecker.Mark> platformMarks14() {
        String cobble = "minecraft:cobblestone";
        List<CompletionChecker.Mark> marks = new ArrayList<>();
        // 四角 + 四边中点 + 中心（14×14 → 索引 0..13）
        for (int x : new int[]{0, 7, 13}) {
            for (int z : new int[]{0, 7, 13}) {
                marks.add(new CompletionChecker.Mark(x, 6, z, cobble));
            }
        }
        return marks;
    }

    private static void put(FacilityTemplate t) {
        BY_ID.put(t.id(), t);
    }

    public static List<FacilityTemplate> all() {
        return List.copyOf(BY_ID.values());
    }

    public static Optional<FacilityTemplate> byId(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(BY_ID.get(id.toLowerCase(java.util.Locale.ROOT).trim()));
    }

    public static boolean has(String id) {
        return byId(id).isPresent();
    }

    /**
     * 按占地格数筛出"能放进这些格"的模板（用户第一版只做相邻独立摆放，
     * 所以这里只是尺寸筛选，不做合并）。
     */
    public static List<FacilityTemplate> fitting(int availableCellsX, int availableCellsZ) {
        List<FacilityTemplate> out = new ArrayList<>();
        for (FacilityTemplate t : BY_ID.values()) {
            if (t.footprintCellsX() <= availableCellsX && t.footprintCellsZ() <= availableCellsZ) {
                out.add(t);
            }
        }
        return out;
    }

    /** 模板材料的缺口（对照给定库存），用于放置前的料单预检。 */
    public static Map<String, Integer> materialGap(FacilityTemplate template, Map<String, Integer> stock) {
        Map<String, Integer> gap = new LinkedHashMap<>();
        Map<String, Integer> have = stock == null ? Map.of() : stock;
        for (MaterialNeed need : template.materials()) {
            int owned = have.getOrDefault(need.itemId(), 0);
            if (owned < need.count()) {
                gap.put(need.itemId(), need.count() - owned);
            }
        }
        return gap;
    }
}
