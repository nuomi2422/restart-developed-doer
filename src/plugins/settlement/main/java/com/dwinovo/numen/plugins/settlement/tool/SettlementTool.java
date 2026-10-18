package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.McWorldProbe;
import com.dwinovo.numen.plugins.settlement.SettlementMonitor;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.logic.CompletionChecker;
import com.dwinovo.numen.settlement.core.logic.PlacementLedger;
import com.dwinovo.numen.settlement.core.logic.PlacementMath;
import com.dwinovo.numen.settlement.core.logic.PlacementValidator;
import com.dwinovo.numen.settlement.core.logic.TemplateCatalog;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.ConstructionStatus;
import com.dwinovo.numen.settlement.core.model.DimAnchor;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;
import com.dwinovo.numen.settlement.core.model.Verdict;
import com.dwinovo.numen.settlement.core.protect.Grant;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ★ 基建工具 V1 的统一入口 —— 「AI 看地图、选模块、选地块；工具负责换算坐标、检查、
 * 施工和登记」。
 *
 * <p>AI 只需要决定<b>建什么、放哪儿</b>：
 * <pre>
 * settlement action=overview                    看主基地地图 + 设施状态 + 空闲格
 * settlement action=catalog                     看可选模块、占几格、材料、可用程度
 * settlement action=place template=pen_basic cell=B2 [rotation=0]
 * settlement action=inspect id=pen_01           看入口/用途/内容/完成情况
 * settlement action=resume  id=pen_01           补料/排阻后继续原设施施工
 * </pre>
 *
 * <p>不必再让 AI 自己串"生成蓝图 → 算锚点 → 登记 → 开权限 → 建造 → 撤权限"这一长串。
 *
 * <h2>流程（用户点名的确定流程）</h2>
 * <pre>
 * 选模板和地块 → 检查（范围/冲突/材料/入口）→ 建稳定设施 id、预留地块并保存施工记录
 * → 取得本任务限定范围的施工权限 → 调现有蓝图建造器 → 回收权限、检查实际结果
 * → 更新设施状态与地图
 * </pre>
 *
 * <h2>三条硬要求怎么落实</h2>
 * <ol>
 *   <li><b>开工前就登记为施工中</b>：{@code place} 在校验通过后<b>立刻</b>写一条
 *       {@link ConstructionStatus#PLANNED} 的登记再动手；所以中途停止/重启后地图显示的是
 *       "施工中"，不会当成空地让 AI 再建第二座。</li>
 *   <li><b>重复调用识别已有设施</b>：设施 id 由 (基地, 模板, 格) 确定性生成
 *       （{@link PlacementLedger#facilityId}），第二次同样的请求必然撞上第一次的账，
 *       走 {@link PlacementLedger.Disposition#RESUME} 而不是新建。</li>
 *   <li><b>所有终止路径回收授权</b>：授权进 {@code GrantLedger} 并绑定本次任务 id，
 *       由服务端的 tick 清扫（任务结束/被顶替/过期都会撤）。</li>
 * </ol>
 *
 * <p>本工具<b>复用</b>既有能力而不是重写：蓝图生成仍走 {@code settlement_pen/house/farm/platform}，
 * 施工仍走 core 的 {@code blueprint action=build}。它只是把这一长串收成一次明确的放置请求。
 */
public final class SettlementTool implements NumenTool {

    private final SettlementService service;

    public SettlementTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement"; }

    @Override public String description() {
        return "★ 基地建设的统一入口：看地图/看模块目录/选格放置/查设施/续建，一次调用把"
                + "『生成蓝图→算锚点→登记→开施工权限→建造→回收权限→更新地图』整串做完。"
                + "AI 只需决定建什么、放哪儿。\n"
                + "action=overview —— 出基地网格地图 + 各设施状态 + 空格提示（先看这个）。\n"
                + "action=survey template=<id> —— 勘测基地里每块空格的地形（逐列读方块高度），"
                + "按『已平 / 需削高 / 需洼填 / 上方障碍』排名，帮你挑最平最空的一格"
                + "（放地皮前先跑它，别把地皮修在树/山坡/建筑上）。\n"
                + "action=catalog —— 出可选模板：占几格、材料、入口朝向、以及『可用程度』"
                + "（可完整建造 / 仅结构可建 / 可试建与续建），未自动化的工序会如实列出。\n"
                + "action=place template=<id> cell=<如 B2> [rotation=0|90|180|270] —— 检查范围/冲突/"
                + "材料/入口，通过则预留地块、登记为施工中、开本任务施工权限、开始建造。"
                + "同一个 (模板,格) 重复调用会识别为续建，不会建第二座；重叠/越界/入口被堵会在"
                + "动土前直接拒绝。\n"
                + "action=inspect id=<设施id> —— 看入口内外、范围、结构/生产判定、施工账与还差什么。\n"
                + "action=resume id=<设施id> —— 补料或排除阻挡后继续原设施施工（沿用原模板/锚点/朝向）。\n"
                + "先 settlement_base action=set 登记基地基准网格（固定原点，不随站位漂移）。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "overview=看地图 / catalog=看模块 / survey=勘测地块 / place=放置 / inspect=查设施 / resume=续建",
                        "overview", "catalog", "survey", "place", "inspect", "resume")
                .optionalString("template", "place/survey 用：模板 id（见 catalog），如 platform_cobble14")
                .optionalString("cell", "place 用：目标格，如 B2（列字母+行号，从 A1 起）")
                .optionalInteger("rotation", "place 用：顺时针旋转角度 0/90/180/270，默认 0", 0, 270)
                .optionalString("id", "inspect/resume 用：设施 id")
                .optionalBool("dry_run", "place 用：只做检查不施工，默认 false")
                .optionalInteger("cols", "overview 用：列数（不给用基地基准）", 1, 26)
                .optionalInteger("rows", "overview 用：行数（不给用基地基准）", 1, 26)
                .optionalInteger("limit", "survey 用：返回前几名的空地块，默认 8", 1, 40)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement: not on a server level").toJson());
                return;
            }
            String action = args.has("action") ? args.get("action").getAsString().toLowerCase(Locale.ROOT) : "";
            switch (action) {
                case "overview" -> overview(args, self, reply);
                case "catalog" -> catalog(reply);
                case "survey" -> survey(args, self, reply);
                case "place" -> place(toolCallId, args, self, reply);
                case "inspect" -> inspect(args, self, reply);
                case "resume" -> resume(toolCallId, args, self, reply);
                default -> reply.accept(TaskResult.fail(
                        "settlement: action must be overview|catalog|survey|place|inspect|resume").toJson());
            }
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement error: " + ex.getMessage()).toJson());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // overview —— 看地图 + 设施状态 + 空闲格
    // ══════════════════════════════════════════════════════════════════

    private void overview(JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Optional<PlatformPlan> maybePlan = service.platformPlan();
        if (maybePlan.isEmpty()) {
            reply.accept(TaskResult.fail("settlement: 还没有基地基准网格。先 "
                    + "settlement_base action=set 登记原点/行列/每格边长（原点必须固定，不能随站位漂移）").toJson());
            return;
        }
        PlatformPlan plan = maybePlan.get();
        List<FacilityRecord> mine = owned(self);

        int cols = args.has("cols") ? clamp(args.get("cols").getAsInt(), 1, 26) : plan.cellsX();
        int rows = args.has("rows") ? clamp(args.get("rows").getAsInt(), 1, 26) : plan.cellsZ();

        // 每格 -> 占用它的设施
        Map<CellKey, FacilityRecord> occupied = new LinkedHashMap<>();
        Map<String, String> letterOf = new LinkedHashMap<>();
        char[] letters = "abcdefghijklmnopqrstuvwxyz".toCharArray();
        int li = 0;
        for (FacilityRecord f : mine) {
            String letter = String.valueOf(letters[li % 26]);
            boolean placed = false;
            for (CellKey c : cellsOf(f, plan)) {
                if (occupied.putIfAbsent(c, f) == null) placed = true;
            }
            if (placed) {
                letterOf.put(f.id(), letter);
                li++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("基地 ").append(plan.dimension())
                .append("  原点=(").append(plan.originX()).append(',').append(plan.floorY())
                .append(',').append(plan.originZ()).append(")  每格 ").append(plan.cellSize())
                .append("×").append(plan.cellSize()).append("  北↑\n     ");
        for (int cx = 0; cx < cols; cx++) sb.append((char) ('A' + cx)).append("  ");
        sb.append('\n');
        List<String> freeCells = new ArrayList<>();
        for (int cz = 0; cz < rows; cz++) {
            sb.append(String.format("%4d ", cz + 1));
            for (int cx = 0; cx < cols; cx++) {
                FacilityRecord f = occupied.get(CellKey.of(cx, cz));
                if (f == null) {
                    sb.append(".  ");
                    if (freeCells.size() < 40) freeCells.add(PlacementValidator.cellName(CellKey.of(cx, cz)));
                } else {
                    sb.append(letterOf.getOrDefault(f.id(), "?")).append("  ");
                }
            }
            sb.append('\n');
        }

        sb.append('\n');
        if (mine.isEmpty()) {
            sb.append("（基地里还没有设施）\n");
        } else {
            for (FacilityRecord f : mine) {
                sb.append(letterOf.getOrDefault(f.id(), "?")).append("：").append(f.id())
                        .append("  ").append(f.kind().name())
                        .append("  结构=").append(f.structureVerdict())
                        .append("  生产=").append(f.productionState());
                ConstructionProgress c = f.construction();
                if (c.status() != ConstructionStatus.UNTRACKED) {
                    sb.append("  施工=").append(c.status());
                    if (c.total() > 0) sb.append(' ').append(c.completed()).append('/').append(c.total());
                    if (!c.missing().isEmpty()) sb.append(" 缺料=").append(c.missing());
                }
                sb.append('\n');
            }
        }
        sb.append("\n空闲格（前 40）：").append(freeCells.isEmpty() ? "无" : String.join(" ", freeCells));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dimension", plan.dimension());
        data.put("origin", plan.originX() + "," + plan.floorY() + "," + plan.originZ());
        data.put("cell_size", plan.cellSize());
        data.put("facilities", mine.size());
        data.put("free_cells", freeCells);
        reply.accept(TaskResult.ok(sb.toString(), data).toJson());
    }

    // ══════════════════════════════════════════════════════════════════
    // catalog —— 模块目录（含可用程度）
    // ══════════════════════════════════════════════════════════════════

    private void catalog(Consumer<String> reply) {
        StringBuilder sb = new StringBuilder();
        sb.append("可选模块（占地按网格向上取整；每格 ")
                .append(TemplateCatalog.DEFAULT_CELL_SIZE).append("×")
                .append(TemplateCatalog.DEFAULT_CELL_SIZE).append("）：\n");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (FacilityTemplate t : TemplateCatalog.all()) {
            sb.append("\n● ").append(t.id()).append("  ").append(t.displayName())
                    .append("  [").append(t.kind().name()).append("]\n")
                    .append("  蓝图 ").append(t.sizeX()).append('×').append(t.sizeZ())
                    .append("  占地 ").append(t.footprintCellsX()).append('×')
                    .append(t.footprintCellsZ()).append(" 格")
                    .append("  入口朝 ").append(t.entranceFacing()).append('\n')
                    .append("  底座基准点（局部）=(").append(t.baseLocal().x()).append(',')
                    .append(t.baseLocal().z()).append(")  最底层相对 floorY 偏移 ")
                    .append(t.anchorYOffset()).append('\n')
                    .append("  可用程度：").append(t.maturity().label()).append('\n');
            if (!t.materials().isEmpty()) {
                sb.append("  材料：");
                List<String> mats = new ArrayList<>();
                for (FacilityTemplate.MaterialNeed m : t.materials()) {
                    mats.add(m.itemId().replace("minecraft:", "") + "×" + m.count());
                }
                sb.append(String.join(" + ", mats)).append('\n');
            }
            for (String step : t.unfinishedSteps()) {
                sb.append("  ⚠ 未自动化：").append(step).append('\n');
            }
            if (t.notes() != null && !t.notes().isBlank()) {
                sb.append("  说明：").append(t.notes()).append('\n');
            }

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", t.id());
            e.put("kind", t.kind().name());
            e.put("size", t.sizeX() + "x" + t.sizeZ());
            e.put("footprint", t.footprintCellsX() + "x" + t.footprintCellsZ());
            e.put("entrance_facing", t.entranceFacing());
            e.put("base_local", t.baseLocal().x() + "," + t.baseLocal().z());
            e.put("base_y_offset", t.anchorYOffset());
            e.put("maturity", t.maturity().name());
            e.put("unfinished", t.unfinishedSteps());
            entries.add(e);
        }
        sb.append("\n放置：settlement action=place template=<id> cell=<如 B2>");
        reply.accept(TaskResult.ok(sb.toString(), Map.of("templates", entries)).toJson());
    }

    // ══════════════════════════════════════════════════════════════════
    // place —— 检查 → 预留 → 登记施工中 → 开权限 → 建造
    // ══════════════════════════════════════════════════════════════════

    private void place(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        if (!args.has("template") || !args.has("cell")) {
            reply.accept(TaskResult.fail("settlement place: 需要 template 和 cell（如 cell=B2）").toJson());
            return;
        }
        Optional<PlatformPlan> maybePlan = service.platformPlan();
        if (maybePlan.isEmpty()) {
            reply.accept(TaskResult.fail("settlement place: 还没有基地基准网格，先 settlement_base action=set").toJson());
            return;
        }
        PlatformPlan plan = maybePlan.get();

        String templateId = args.get("template").getAsString().toLowerCase(Locale.ROOT).trim();
        Optional<FacilityTemplate> maybeTemplate = TemplateCatalog.byId(templateId);
        if (maybeTemplate.isEmpty()) {
            reply.accept(TaskResult.fail("settlement place: 未知模板 '" + templateId
                    + "'；可选：" + TemplateCatalog.all().stream().map(FacilityTemplate::id).toList()).toJson());
            return;
        }
        FacilityTemplate template = maybeTemplate.get();

        CellKey cell = parseCell(args.get("cell").getAsString());
        if (cell == null) {
            reply.accept(TaskResult.fail("settlement place: 格子写法不对（要像 B2：列字母+行号）").toJson());
            return;
        }
        int quarters = args.has("rotation")
                ? Math.floorMod(args.get("rotation").getAsInt(), 360) / 90 : 0;
        boolean dryRun = args.has("dry_run") && args.get("dry_run").getAsBoolean();

        String facilityId = PlacementLedger.facilityId(
                service.base().map(SettlementService.BaseGrid::dimension).orElse("base"),
                template.id(), PlacementValidator.cellName(cell));

        // ① 去重 / 续建判定（重复请求不许建第二座）
        PlacementLedger.Decision decision = PlacementLedger.decide(service.registry(), facilityId);
        if (!decision.disposition().canStart()) {
            reply.accept(TaskResult.fail("settlement place: " + decision.reason()).toJson());
            return;
        }

        // ② 占地校验（越界/重叠/入口空间）——必须在动世界之前
        PlacementValidator.Result check = PlacementValidator.validate(plan, cell, template, quarters,
                service.registry(), plan.dimension(), decision.existing() == null ? null : facilityId);
        if (!check.ok()) {
            SettlementMonitor.publish("place_rejected", Map.of(
                    "template", template.id(),
                    "cell", PlacementValidator.cellName(cell),
                    "stage", "validate",
                    "reason", String.join("；", check.rejects())));
            reply.accept(TaskResult.fail("settlement place 被拒绝（未改世界）："
                    + String.join("；", check.rejects())).toJson());
            return;
        }

        PlacementMath.PlacementPlan placement = PlacementMath.resolve(plan, cell, template, quarters);

        // ③ 续建时校验锚点/朝向没变（基地基准改过就拒绝，否则建到别处）
        if (decision.disposition() == PlacementLedger.Disposition.RESUME) {
            List<String> mismatch = PlacementLedger.resumeMismatches(decision.existing(), template, placement);
            if (!mismatch.isEmpty()) {
                reply.accept(TaskResult.fail("settlement place: 不能按原样续建——"
                        + String.join("；", mismatch)).toJson());
                return;
            }
        }

        // ③b 朴素场地评估（用户 2026-10-09 晚点名）：选点后扫这块地，数一数"上方压了多少、
        //     下方多深坑"，让 AI 自己决定换不换格，而不是硬修出一个坑。非致命，只入 warnings。
        List<String> warnings = new ArrayList<>(check.warnings());
        warnings.addAll(assessSite(self.serverLevel(), placement.footprintBox(), plan.floorY()));

        // 底座基准点（用户 2026-10-10 点名）：每个模块蓝图都带一个"底座坐标标记"，
        // 说明它站哪、和地基哪一层合并。这里把它算到世界坐标，并标出它坐的地基 Y 层。
        DimAnchor basePt = PlacementMath.basePoint(placement, template);

        if (dryRun) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dry_run", true);
            data.put("facility_id", facilityId);
            data.put("disposition", decision.disposition().name());
            data.put("anchor", anchorText(placement.anchor()));
            data.put("base", anchorText(basePt) + "（底座基准点；坐在地基 y=" + (basePt.y() - 1) + " 上）");
            data.put("base_local", template.baseLocal().x() + "," + template.baseLocal().z());
            data.put("docks_on_y", basePt.y() - 1);
            data.put("warnings", warnings);
            reply.accept(TaskResult.ok("检查通过（dry_run，未动世界）。设施 id=" + facilityId
                    + "，处置=" + decision.disposition(), data).toJson());
            return;
        }

        // ④ 生成蓝图（复用既有生成器）
        //
        // ★ 生成器收的是"中心"，而放置解算给的是"最小角锚点"——两者不能混用：
        //   pen/house 的 minX = cx - size/2；farm 的 floor_y 默认取脚下；
        //   platform 更是按锚点周围的地形现场整平（位置相关！）。
        //   所以这里按模板把"锚点"翻译成生成器各自要的参数，而不是把锚点当中心传进去。
        String genTool = generatorToolFor(template.id());
        if (genTool == null) {
            reply.accept(TaskResult.fail("settlement place: 模板 '" + template.id()
                    + "' 还没有可用的生成器（仅设计阶段）").toJson());
            return;
        }
        JsonObject genArgs = new JsonObject();
        genArgs.addProperty("name", facilityId);
        int centerX = placement.anchor().x() + template.sizeX() / 2;
        int centerZ = placement.anchor().z() + template.sizeZ() / 2;
        switch (genTool) {
            case "settlement_pen" -> {
                genArgs.addProperty("animal", "pen_cow".equals(template.id()) ? "cow" : "sheep");
                genArgs.addProperty("size", 7);
                genArgs.addProperty("cx", centerX);
                genArgs.addProperty("cy", placement.anchor().y());
                genArgs.addProperty("cz", centerZ);
                genArgs.addProperty("gate_side", template.entranceFacing());
            }
            case "settlement_house" -> {
                genArgs.addProperty("cx", centerX);
                genArgs.addProperty("cy", placement.anchor().y());
                genArgs.addProperty("cz", centerZ);
            }
            case "settlement_farm" -> {
                // 农田锚点 = floorY-1（y=0 是支撑泥土），所以 floor_y = anchor.y + 1
                genArgs.addProperty("cx", centerX);
                genArgs.addProperty("cz", centerZ);
                genArgs.addProperty("size", 7);
                genArgs.addProperty("floor_y", placement.anchor().y() + 1);
            }
            case "settlement_trade" -> {
                genArgs.addProperty("cx", centerX);
                genArgs.addProperty("cy", placement.anchor().y());
                genArgs.addProperty("cz", centerZ);
            }
            case "settlement_platform" -> {
                // 平台蓝图位置相关（按现场地形填低削高）：给中心 + floor_y + 圆石打底。
                // ★ 2026-10-09 晚用户点名把整平/清空调大：清够高（树/坡）、填够深（洼地），
                //   才是"顶面平、上方空、下面实心"的真地皮。旧的 depth=1/clear=1 只削一层、
                //   只清一层，看着又没平又没清。
                // ★ floor_y 必须补偿 depth：平台蓝图锚点是"局部 0 = floor_y-depth"，
                //   而放置锚点 = floorY + anchorYOffset(= -depth)；floor_y 取 anchor.y+depth
                //   两边才对齐，顶面（局部 y=depth）落回 floorY。
                final int depth = 6;
                final int clear = 10;
                genArgs.addProperty("cx", centerX);
                genArgs.addProperty("cz", centerZ);
                genArgs.addProperty("size", 14);
                genArgs.addProperty("floor_y", placement.anchor().y() + depth);
                genArgs.addProperty("depth", depth);
                genArgs.addProperty("clear_height", clear);
                genArgs.addProperty("floor_block", "minecraft:cobblestone");
            }
            default -> { }
        }
        JsonObject genReply = callTool(genTool, self, genArgs);
        if (genReply == null || !genReply.has("success") || !genReply.get("success").getAsBoolean()) {
            reply.accept(TaskResult.fail("settlement place: 生成蓝图失败（" + genTool + "）："
                    + (genReply == null ? "无回执" : genReply)).toJson());
            return;
        }
        String blueprintName = genReply.has("data") && genReply.getAsJsonObject("data").has("blueprint")
                ? genReply.getAsJsonObject("data").get("blueprint").getAsString() : facilityId;
        int cells = genReply.has("data") && genReply.getAsJsonObject("data").has("cells")
                ? genReply.getAsJsonObject("data").get("cells").getAsInt() : 0;

        long now = System.currentTimeMillis();
        String taskId = "pending-" + now;

        // ⑤ 开工前先登记为施工中（用户点名的硬要求）
        FacilityRecord record = buildRecord(facilityId, self, plan, template, placement,
                ConstructionProgress.opened(template.id(), placement.anchor(), taskId, cells,
                        ConstructionStatus.PLANNED, now));
        service.register(record);

        // ⑥ 开本任务限定范围的施工权限
        List<Grant> grantList = new ArrayList<>();
        List<BlockBox> zones = record.workZones().isEmpty() ? List.of(record.bounds()) : record.workZones();
        for (BlockBox zone : zones) {
            grantList.add(Grant.expiring(facilityId, plan.dimension(), zone, 0L));
        }
        service.grantLedger().open(facilityId, taskId, Grant.of(facilityId, plan.dimension(), record.bounds()));

        // ⑦ 调既有建造器
        JsonObject buildArgs = new JsonObject();
        buildArgs.addProperty("action", "build");
        buildArgs.addProperty("file", blueprintName);
        buildArgs.addProperty("x", placement.anchor().x());
        buildArgs.addProperty("y", placement.anchor().y());
        buildArgs.addProperty("z", placement.anchor().z());
        buildArgs.addProperty("rotation", quarters * 90);
        JsonObject buildReply = callTool("blueprint", self, buildArgs);
        boolean accepted = buildReply != null && buildReply.has("success")
                && buildReply.get("success").getAsBoolean();

        // ⑧ 施工账推进到 BUILDING（失败则 FAILED 并立刻回收授权）
        if (accepted) {
            String realTaskId = buildReply.has("data") && buildReply.getAsJsonObject("data").has("task_id")
                    ? buildReply.getAsJsonObject("data").get("task_id").getAsString() : taskId;
            service.register(record.withConstruction(
                    record.construction().withTask(realTaskId, System.currentTimeMillis())
                            .withStatus(ConstructionStatus.BUILDING, System.currentTimeMillis())));
            service.grantLedger().open(facilityId, realTaskId,
                    Grant.of(facilityId, plan.dimension(), record.bounds()));
            service.addGrants(grantList);
        } else {
            service.register(record.withConstruction(
                    record.construction().withStatus(ConstructionStatus.FAILED, System.currentTimeMillis())
                            .withMessage("建造器未受理", System.currentTimeMillis())));
            service.grantLedger().release(facilityId);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facility_id", facilityId);
        data.put("template", template.id());
        data.put("cell", PlacementValidator.cellName(cell));
        data.put("disposition", decision.disposition().name());
        data.put("anchor", anchorText(placement.anchor()));
        data.put("base", anchorText(basePt) + "（底座基准点；坐在地基 y=" + (basePt.y() - 1) + " 上）");
        data.put("base_local", template.baseLocal().x() + "," + template.baseLocal().z());
        data.put("docks_on_y", basePt.y() - 1);
        data.put("entrance_inside", anchorText(placement.entranceInside()));
        data.put("entrance_outside", anchorText(placement.entranceOutside()));
        data.put("cells", cells);
        data.put("granted_areas", zones.size());
        data.put("warnings", warnings);
        data.put("build_accepted", accepted);
        if (accepted && buildReply.has("data")) {
            data.put("build", buildReply.get("data").toString());
        }
        data.put("next", "task_finished 后用 settlement action=inspect id=" + facilityId
                + " 看施工账；缺料就补料再 settlement action=resume id=" + facilityId);
        SettlementMonitor.publish("place", Map.of(
                "facility_id", facilityId,
                "template", template.id(),
                "cell", PlacementValidator.cellName(cell),
                "disposition", decision.disposition().name(),
                "anchor", anchorText(placement.anchor()),
                "base", anchorText(basePt),
                "docks_on_y", basePt.y() - 1,
                "accepted", accepted));
        reply.accept(TaskResult.ok((accepted ? "已受理施工" : "建造器未受理（已回滚授权）")
                + "：" + facilityId + " @ " + PlacementValidator.cellName(cell)
                + "（" + decision.disposition() + "）", data).toJson());
    }

    // ══════════════════════════════════════════════════════════════════
    // inspect / resume
    // ══════════════════════════════════════════════════════════════════

    private void inspect(JsonObject args, NumenPlayer self, Consumer<String> reply) {
        if (!args.has("id")) {
            reply.accept(TaskResult.fail("settlement inspect: 需要 id").toJson());
            return;
        }
        String id = args.get("id").getAsString().toLowerCase(Locale.ROOT).trim();
        Optional<FacilityRecord> found = service.byId(id);
        if (found.isEmpty()) {
            reply.accept(TaskResult.fail("settlement inspect: 没有设施 '" + id + "'").toJson());
            return;
        }
        FacilityRecord f = found.get();

        // 世界侧核对施工收口（任务说"结束"≠建好了）；顺手把结果回写施工账。
        CompletionChecker.Result completion = null;
        ConstructionProgress c = f.construction();
        if (c.template() != null && c.anchor() != null && self != null && self.getServer() != null) {
            Optional<FacilityTemplate> tpl = TemplateCatalog.byId(c.template());
            if (tpl.isPresent() && !tpl.get().marks().isEmpty()) {
                FacilityTemplate t = tpl.get();
                completion = CompletionChecker.check(t.marks(), c.anchor(), t.sizeX(), t.sizeZ(),
                        f.rotationQuarters(), new McWorldProbe(self.getServer()));
                ConstructionStatus next = c.status();
                if (completion.complete()) {
                    next = ConstructionStatus.COMPLETE;
                } else if (c.status() == ConstructionStatus.BUILDING
                        || c.status() == ConstructionStatus.PLANNED) {
                    next = ConstructionStatus.BLOCKED;
                }
                c = new ConstructionProgress(completion.matched(), c.skipped(), c.droppedAtLoad(),
                        completion.total(), System.currentTimeMillis(), next, c.template(),
                        c.anchor(), c.taskId(), c.missing(),
                        completion.complete() ? "世界核对：结构标记全部命中"
                                : "世界核对：还差 " + completion.missingCount() + " 处结构标记");
                f = f.withConstruction(c);
                service.register(f);
            }
        }

        BlockBox b = f.bounds();
        StringBuilder sb = new StringBuilder();
        sb.append("设施 ").append(f.id()).append("  (").append(f.kind().name()).append(")\n")
                .append("维度=").append(f.dimension()).append('\n')
                .append("范围=").append(b.minX()).append(',').append(b.minY()).append(',').append(b.minZ())
                .append(" .. ").append(b.maxX()).append(',').append(b.maxY()).append(',').append(b.maxZ()).append('\n');
        if (f.entranceOutside() != null) {
            sb.append("入口外=").append(anchorText(f.entranceOutside())).append(' ');
        }
        if (f.entranceInside() != null) {
            sb.append("入口内=").append(anchorText(f.entranceInside()));
        }
        sb.append('\n').append("结构=").append(f.structureVerdict())
                .append("  生产=").append(f.productionState()).append('\n');
        sb.append("施工=").append(c.status());
        if (c.total() > 0) sb.append(' ').append(c.completed()).append('/').append(c.total());
        sb.append("  模板=").append(c.template() == null ? "(未走放置流程)" : c.template());
        if (c.anchor() != null) sb.append("  锚点=").append(anchorText(c.anchor()));
        sb.append('\n');
        if (completion != null) {
            sb.append("世界核对=").append(completion.matched()).append('/').append(completion.total());
            if (!completion.complete()) {
                sb.append("  还差=").append(completion.missing());
            }
            sb.append('\n');
        }
        if (!c.missing().isEmpty()) {
            sb.append("缺料=").append(c.missing()).append('\n');
        }
        if (c.message() != null && !c.message().isBlank()) {
            sb.append("备注=").append(c.message()).append('\n');
        }
        if (c.isOpen()) {
            sb.append("→ 施工未收口：补料/排阻后 settlement action=resume id=").append(f.id());
        } else if (c.status() == ConstructionStatus.COMPLETE) {
            sb.append("→ 施工已收口；结构/生产判定见上（施工完成 ≠ 已投产）");
        } else if (c.status() == ConstructionStatus.UNTRACKED) {
            sb.append("→ 这是手工登记的设施（无施工账）");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", f.id());
        data.put("kind", f.kind().name());
        data.put("structure", f.structureVerdict().name());
        data.put("production", f.productionState().name());
        data.put("construction", c.status().name());
        data.put("construction_total", c.total());
        data.put("construction_completed", c.completed());
        data.put("missing", c.missing());
        data.put("template", c.template());
        if (completion != null) {
            data.put("marks_matched", completion.matched());
            data.put("marks_total", completion.total());
            data.put("marks_missing", completion.missing());
        }
        SettlementMonitor.publish("inspect", Map.of(
                "id", f.id(),
                "kind", f.kind().name(),
                "construction", c.status().name(),
                "completed", c.completed(),
                "total", c.total()));
        reply.accept(TaskResult.ok(sb.toString(), data).toJson());
    }

    private void resume(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        if (!args.has("id")) {
            reply.accept(TaskResult.fail("settlement resume: 需要 id").toJson());
            return;
        }
        String id = args.get("id").getAsString().toLowerCase(Locale.ROOT).trim();
        Optional<FacilityRecord> found = service.byId(id);
        if (found.isEmpty()) {
            reply.accept(TaskResult.fail("settlement resume: 没有设施 '" + id + "'").toJson());
            return;
        }
        FacilityRecord f = found.get();
        ConstructionProgress c = f.construction();
        if (c.status() == ConstructionStatus.COMPLETE) {
            // ★ 账上"收口"不等于世界里建完了：施工账可能停在 COMPLETE 而世界侧还有缺失
            //   （2026-10-09 实测 D3 核心屋：账=COMPLETE，世界核对=14/15 缺箱子）。
            //   所以这里必须**先问世界**再决定要不要拒绝续建——否则缺的那件东西
            //   永远补不上（resume 被自己拦掉）。
            if (self != null && self.getServer() != null && c.template() != null && c.anchor() != null) {
                Optional<FacilityTemplate> tpl = TemplateCatalog.byId(c.template());
                if (tpl.isPresent() && !tpl.get().marks().isEmpty()) {
                    FacilityTemplate t = tpl.get();
                    CompletionChecker.Result marks = CompletionChecker.check(t.marks(), c.anchor(),
                            t.sizeX(), t.sizeZ(), f.rotationQuarters(), new McWorldProbe(self.getServer()));
                    if (!marks.complete()) {
                        // 世界说没建完 → 允许续建，并把账重新打开
                        long at = System.currentTimeMillis();
                        c = new ConstructionProgress(marks.matched(), c.skipped(), c.droppedAtLoad(),
                                marks.total(), at, ConstructionStatus.BLOCKED, c.template(),
                                c.anchor(), c.taskId(), c.missing(),
                                "世界核对：还差 " + marks.missingCount() + " 处 → 重新开工");
                        f = f.withConstruction(c);
                        service.register(f);
                    } else {
                        reply.accept(TaskResult.fail("settlement resume: '" + id
                                + "' 已收口且世界核对通过（" + marks.matched() + "/" + marks.total()
                                + "），无需续建").toJson());
                        return;
                    }
                }
            }
            if (c.status() == ConstructionStatus.COMPLETE) {
                reply.accept(TaskResult.fail("settlement resume: '" + id + "' 已收口，无需续建").toJson());
                return;
            }
        }
        if (c.template() == null || c.anchor() == null) {
            reply.accept(TaskResult.fail("settlement resume: '" + id
                    + "' 没有施工账（不是放置流程建的），无法续建").toJson());
            return;
        }
        // 续建沿用原锚点与朝向（不重新解算，避免基地基准变动导致错位）
        JsonObject buildArgs = new JsonObject();
        buildArgs.addProperty("action", "build");
        buildArgs.addProperty("file", f.blueprintName() == null ? id : f.blueprintName());
        buildArgs.addProperty("x", c.anchor().x());
        buildArgs.addProperty("y", c.anchor().y());
        buildArgs.addProperty("z", c.anchor().z());
        buildArgs.addProperty("rotation", f.rotationQuarters() * 90);

        JsonObject buildReply = callTool("blueprint", self, buildArgs);
        boolean accepted = buildReply != null && buildReply.has("success")
                && buildReply.get("success").getAsBoolean();
        String taskId = accepted && buildReply.has("data")
                && buildReply.getAsJsonObject("data").has("task_id")
                ? buildReply.getAsJsonObject("data").get("task_id").getAsString() : null;

        long now = System.currentTimeMillis();
        service.register(f.withConstruction(accepted
                ? c.withStatus(ConstructionStatus.BUILDING, now)
                    .withTask(taskId == null ? c.taskId() : taskId, now)
                : c.withStatus(ConstructionStatus.FAILED, now).withMessage("续建未被受理", now)));
        if (accepted) {
            service.grantLedger().open(id, taskId == null ? c.taskId() : taskId,
                    Grant.of(id, f.dimension(), f.bounds()));
        } else {
            service.grantLedger().release(id);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("accepted", accepted);
        data.put("anchor", anchorText(c.anchor()));
        data.put("outstanding", c.outstanding());
        SettlementMonitor.publish("resume", Map.of(
                "id", id,
                "accepted", accepted,
                "anchor", anchorText(c.anchor()),
                "outstanding", c.outstanding()));
        reply.accept(TaskResult.ok((accepted ? "已受理续建" : "续建未被受理") + "：" + id
                + "（还差 " + c.outstanding() + " 格）", data).toJson());
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /** 模板 id → 生成器工具名。目录里只有真有生成器的模板才可放置。 */
    private static String generatorToolFor(String templateId) {
        return switch (templateId) {
            case "pen_sheep", "pen_cow" -> "settlement_pen";
            case "core_house" -> "settlement_house";
            case "farm_basic" -> "settlement_farm";
            case "trade_post" -> "settlement_trade";
            case "platform_cobble14" -> "settlement_platform";
            default -> null;
        };
    }

    /** 在服务端线程直接调另一个工具（与 ACX 的桥同路径），拿回执 JSON。 */
    private JsonObject callTool(String toolName, NumenPlayer self, JsonObject args) {
        NumenTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            return null;
        }
        final String[] holder = new String[1];
        tool.onServerCall("settlement-" + toolName + "-" + System.nanoTime(), args, self,
                r -> holder[0] = r);
        if (holder[0] == null) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(holder[0]);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private FacilityRecord buildRecord(String facilityId, NumenPlayer self, PlatformPlan plan,
                                       FacilityTemplate template, PlacementMath.PlacementPlan placement,
                                       ConstructionProgress progress) {
        BlockBox bounds = placement.footprintBox();
        String dimension = plan.dimension();
        DimAnchor inside = placement.entranceInside();
        DimAnchor outside = placement.entranceOutside();

        // 结构保护：围一圈 + 入口留 SPACE（与 settlement_register 的 ringZones 同口径）
        List<ProtectionZone> zones = ringZones(bounds, inside, template.entranceFacing());
        List<BlockBox> workZones = List.of(bounds.expand(-1, 0, -1));

        return new FacilityRecord(facilityId, self.getUUID().toString(), "default", dimension,
                template.kind(), bounds, placement.rotationQuarters(),
                facilityId, "v1", null, zones, workZones, outside, inside,
                List.of(inside), List.of(), placement.cells(), Map.of(),
                progress, Verdict.UNKNOWN, ProductionState.UNKNOWN,
                System.currentTimeMillis(), "placed by settlement action=place");
    }

    /**
     * 保护环：占地四周记 STRUCTURE，入口那一列留 SPACE。
     *
     * <p>入口在环上的位置由蓝图入口的世界坐标决定；若入口不在占地环上（模板入口在内部），
     * 就退回"南墙中点留口"的旧口径。
     */
    private static List<ProtectionZone> ringZones(BlockBox b, DimAnchor entrance, String facing) {
        int y0 = b.minY();
        int y1 = b.maxY();
        List<ProtectionZone> zones = new ArrayList<>();
        String f = facing == null ? "south" : facing.toLowerCase(Locale.ROOT);

        // 四条边各记 STRUCTURE，门所在的边按门的位置挖口
        int gateX = entrance.x();
        int gateZ = entrance.z();
        // 北/南墙（沿 X）
        zones.add(ProtectionZone.structure(BlockBox.of(b.minX(), y0, b.minZ(), b.maxX(), y1, b.minZ())));
        zones.add(ProtectionZone.structure(BlockBox.of(b.minX(), y0, b.maxZ(), b.maxX(), y1, b.maxZ())));
        // 东/西墙（沿 Z）
        zones.add(ProtectionZone.structure(BlockBox.of(b.minX(), y0, b.minZ(), b.minX(), y1, b.maxZ())));
        zones.add(ProtectionZone.structure(BlockBox.of(b.maxX(), y0, b.minZ(), b.maxX(), y1, b.maxZ())));
        // 门所在的格改成 SPACE（通道不能既算结构禁拆、又算通道禁堵）
        if ("north".equals(f) || "south".equals(f)) {
            int z = "north".equals(f) ? b.minZ() : b.maxZ();
            if (gateX >= b.minX() && gateX <= b.maxX()) {
                zones.add(ProtectionZone.space(BlockBox.of(gateX, y0, z, gateX, y1, z)));
            }
        } else {
            int x = "west".equals(f) ? b.minX() : b.maxX();
            if (gateZ >= b.minZ() && gateZ <= b.maxZ()) {
                zones.add(ProtectionZone.space(BlockBox.of(x, y0, gateZ, x, y1, gateZ)));
            }
        }
        return zones;
    }

    // ══════════════════════════════════════════════════════════════════
    // survey —— 勘测地块（逐列读地形，按"已平/削高/洼填/上方障碍"排名）
    // ══════════════════════════════════════════════════════════════════

    /** 逐列地形统计（probe 范围 {@code [floorY-PROBE_DOWN, floorY+PROBE_UP]}）。 */
    private record SiteStats(int total, int flat, int cut, int maxCut, int fill, int maxFill,
                             int tall, int unloaded) {

        /** 越小越好：先看上方障碍，再看最大起伏，再看需动土的列数。 */
        int penalty() {
            return tall * 1000 + Math.max(maxCut, maxFill) * 10 + (cut + fill);
        }

        String describe() {
            return "已平 " + flat + "  需削高 " + cut + "(最高 +" + maxCut + ")  "
                    + "需洼填 " + fill + "(最深 -" + maxFill + ")"
                    + (tall > 0 ? "  ⚠上方障碍 " + tall + " 列" : "");
        }
    }

    private static final int PROBE_UP = 16;
    private static final int PROBE_DOWN = 6;

    /** 单块占地的地形统计（直接扫）。 */
    private static SiteStats statsFor(ServerLevel level, BlockBox foot, int floorY) {
        int total = 0, flat = 0, cut = 0, fill = 0, tall = 0, unloaded = 0, maxCut = 0, maxFill = 0;
        for (int x = foot.minX(); x <= foot.maxX(); x++) {
            for (int z = foot.minZ(); z <= foot.maxZ(); z++) {
                total++;
                if (!level.hasChunkAt(new BlockPos(x, floorY, z))) { unloaded++; continue; }
                int top = floorY - PROBE_DOWN - 1;   // 哨兵：探针范围内全是空气 → 当深坑
                for (int y = floorY + PROBE_UP; y >= floorY - PROBE_DOWN; y--) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) { top = y; break; }
                }
                if (top > floorY) {
                    cut++; maxCut = Math.max(maxCut, top - floorY);
                    if (top - floorY >= 3) tall++;
                } else if (top < floorY) {
                    fill++; maxFill = Math.max(maxFill, floorY - top);
                } else {
                    flat++;
                }
            }
        }
        return new SiteStats(total, flat, cut, maxCut, fill, maxFill, tall, unloaded);
    }

    /**
     * 朴素场地评估（用户 2026-10-09 晚点名"选点后看看周围东西多不多"）：把这个占地的地形
     * 统计成"已平 / 需削高 / 需洼填 / 上方障碍"文案。<b>非致命</b>——只报给 AI，
     * 换不换格由 AI 决定（而不是硬修出一个坑）。
     */
    private static List<String> assessSite(ServerLevel level, BlockBox foot, int floorY) {
        SiteStats s = statsFor(level, foot, floorY);
        List<String> out = new ArrayList<>();
        if (s.unloaded() > 0) {
            out.add("场地评估：有 " + s.unloaded() + " 列区块未加载，评估不全——先走过去再放");
        }
        out.add("场地评估(" + foot.sizeX() + "×" + foot.sizeZ() + "，" + s.total() + " 列)："
                + s.describe());
        if (s.tall() > 0) {
            out.add("⚠ 这块地上方有 " + s.tall() + " 列被 ≥3 格高的东西压着（树/建筑/山坡）——"
                    + "修地皮会削掉它们；建议换更空的格，或确认就是要清这里");
        }
        return out;
    }

    /**
     * 勘测（用户 2026-10-09 晚点名"AI 自己找平地"）：一次性扫整张基地网格的地形高度，
     * 对每块能放下 {@code template} 的空格算出「已平/削高/洼填/上方障碍」，按 penalty 排名，
     * 让 AI 自己挑最平最空的一格——而不是硬修出一个坑。
     */
    private void survey(JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Optional<PlatformPlan> maybePlan = service.platformPlan();
        if (maybePlan.isEmpty()) {
            reply.accept(TaskResult.fail("settlement survey: 还没有基地基准网格，先 settlement_base action=set").toJson());
            return;
        }
        PlatformPlan plan = maybePlan.get();
        String templateId = args.has("template")
                ? args.get("template").getAsString().toLowerCase(Locale.ROOT).trim() : "platform_cobble14";
        Optional<FacilityTemplate> maybeTemplate = TemplateCatalog.byId(templateId);
        if (maybeTemplate.isEmpty()) {
            reply.accept(TaskResult.fail("settlement survey: 未知模板 '" + templateId + "'").toJson());
            return;
        }
        FacilityTemplate template = maybeTemplate.get();
        int limit = args.has("limit") ? clamp(args.get("limit").getAsInt(), 1, 40) : 8;

        ServerLevel level = self.serverLevel();
        int floorY = plan.floorY();
        int ox = plan.originX(), oz = plan.originZ();
        int gw = plan.cellsX() * plan.cellSize();
        int gz = plan.cellsZ() * plan.cellSize();
        // 一次扫描整张网格的高度图（避免逐格重复读方块）。
        int[] tops = new int[gw * gz];
        int unloadedColumns = 0;
        for (int ix = 0; ix < gw; ix++) {
            int wx = ox + ix;
            for (int iz = 0; iz < gz; iz++) {
                int wz = oz + iz;
                if (!level.hasChunkAt(new BlockPos(wx, floorY, wz))) {
                    tops[ix * gz + iz] = Integer.MIN_VALUE;
                    unloadedColumns++;
                    continue;
                }
                int top = floorY - PROBE_DOWN - 1;
                for (int y = floorY + PROBE_UP; y >= floorY - PROBE_DOWN; y--) {
                    if (!level.getBlockState(new BlockPos(wx, y, wz)).isAir()) { top = y; break; }
                }
                tops[ix * gz + iz] = top;
            }
        }

        // 已占用的格（只按"同类"算：放地皮时地皮/模块都算占；放模块时地皮不算占，与放置校验同口径）。
        boolean placingPlatform = template.id().startsWith("platform");
        Set<CellKey> occupied = new LinkedHashSet<>();
        for (FacilityRecord f : service.registry().inDimension(plan.dimension())) {
            boolean existingPlatform = f.construction().template() != null
                    && f.construction().template().startsWith("platform");
            if (existingPlatform != placingPlatform) continue;
            occupied.addAll(cellsOf(f, plan));
        }

        record Candidate(CellKey cell, SiteStats stats) { }
        List<Candidate> candidates = new ArrayList<>();
        for (int cx = 0; cx + template.footprintCellsX() <= plan.cellsX(); cx++) {
            for (int cz = 0; cz + template.footprintCellsZ() <= plan.cellsZ(); cz++) {
                boolean clash = false;
                for (int dx = 0; dx < template.footprintCellsX() && !clash; dx++) {
                    for (int dz = 0; dz < template.footprintCellsZ(); dz++) {
                        if (occupied.contains(CellKey.of(cx + dx, cz + dz))) { clash = true; break; }
                    }
                }
                if (clash) continue;
                PlacementMath.PlacementPlan pl = PlacementMath.resolve(plan, CellKey.of(cx, cz), template, 0);
                candidates.add(new Candidate(CellKey.of(cx, cz),
                        statsFromMap(tops, ox, oz, gw, gz, pl.footprintBox(), floorY)));
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.stats().penalty())
                .thenComparingInt(c -> -c.stats().flat()));

        StringBuilder sb = new StringBuilder();
        sb.append("勘测（模板 ").append(template.id()).append("，占地 ")
                .append(template.footprintCellsX()).append("×").append(template.footprintCellsZ())
                .append(" 格；基准平面 y=").append(floorY).append("）：");
        if (unloadedColumns > 0) {
            sb.append("\n⚠ 有 ").append(unloadedColumns).append(" 列区块未加载——勘测不全，先走过去再来");
        }
        if (candidates.isEmpty()) {
            sb.append("\n没有能放下它的空格（都被占了或越界）。");
        } else {
            sb.append("\n候选空格 ").append(candidates.size()).append(" 个，按『最平最空』排序：");
            for (int i = 0; i < Math.min(limit, candidates.size()); i++) {
                Candidate c = candidates.get(i);
                sb.append("\n  ").append(i + 1).append(". ")
                        .append(PlacementValidator.cellName(c.cell())).append(" —— ")
                        .append(c.stats().describe());
            }
            Candidate best = candidates.get(0);
            sb.append("\n建议：").append(PlacementValidator.cellName(best.cell()))
                    .append("（").append(best.stats().describe()).append("）");
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Candidate c : candidates) {
            SiteStats s = c.stats();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("cell", PlacementValidator.cellName(c.cell()));
            e.put("flat", s.flat());
            e.put("cut", s.cut());
            e.put("max_cut", s.maxCut());
            e.put("fill", s.fill());
            e.put("max_fill", s.maxFill());
            e.put("tall_obstruction", s.tall());
            e.put("penalty", s.penalty());
            entries.add(e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("template", template.id());
        data.put("floor_y", floorY);
        data.put("unloaded_columns", unloadedColumns);
        data.put("candidates", entries);
        data.put("best", candidates.isEmpty() ? null : PlacementValidator.cellName(candidates.get(0).cell()));
        SettlementMonitor.publish("survey", Map.of(
                "template", template.id(),
                "floor_y", floorY,
                "candidates", candidates.size(),
                "best", candidates.isEmpty() ? "(none)" : PlacementValidator.cellName(candidates.get(0).cell()),
                "unloaded_columns", unloadedColumns));
        reply.accept(TaskResult.ok(sb.toString(), data).toJson());
    }

    /** 从预扫的高度图里读一块占地的统计（越出网格的列跳过，未加载单列扣账）。 */
    private static SiteStats statsFromMap(int[] tops, int ox, int oz, int gw, int gz,
                                          BlockBox foot, int floorY) {
        int total = 0, flat = 0, cut = 0, fill = 0, tall = 0, unloaded = 0, maxCut = 0, maxFill = 0;
        for (int x = foot.minX(); x <= foot.maxX(); x++) {
            int ix = x - ox;
            if (ix < 0 || ix >= gw) continue;
            for (int z = foot.minZ(); z <= foot.maxZ(); z++) {
                int iz = z - oz;
                if (iz < 0 || iz >= gz) continue;
                total++;
                int top = tops[ix * gz + iz];
                if (top == Integer.MIN_VALUE) { unloaded++; continue; }
                if (top > floorY) {
                    cut++; maxCut = Math.max(maxCut, top - floorY);
                    if (top - floorY >= 3) tall++;
                } else if (top < floorY) {
                    fill++; maxFill = Math.max(maxFill, floorY - top);
                } else {
                    flat++;
                }
            }
        }
        return new SiteStats(total, flat, cut, maxCut, fill, maxFill, tall, unloaded);
    }

    private List<FacilityRecord> owned(NumenPlayer self) {
        String owner = self.getUUID().toString();
        List<FacilityRecord> out = new ArrayList<>();
        for (FacilityRecord f : service.list()) {
            if (owner.equals(f.ownerId())) out.add(f);
        }
        return out;
    }

    private static java.util.Set<CellKey> cellsOf(FacilityRecord f, PlatformPlan plan) {
        java.util.Set<CellKey> cells = new java.util.LinkedHashSet<>();
        BlockBox b = f.bounds();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                com.dwinovo.numen.settlement.core.logic.PlatformMath.cellAt(plan, x, z).ifPresent(cells::add);
            }
        }
        return cells;
    }

    private static CellKey parseCell(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.length() < 2) return null;
        int cx = s.charAt(0) - 'A';
        if (cx < 0 || cx > 25) return null;
        try {
            int cz = Integer.parseInt(s.substring(1).trim()) - 1;
            if (cz < 0) return null;
            return CellKey.of(cx, cz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String anchorText(DimAnchor a) {
        return a == null ? "(none)" : a.x() + "," + a.y() + "," + a.z();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
