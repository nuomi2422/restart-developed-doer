package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Stage-A：把整条目标（{@code /goal 通关MC}）先规划成 N 个按序推进的一级发展阶段（主题 + 可选跨级资产门）。
 *
 * <p>只产一级清单，<b>绝不展开二级</b>——一级到达后由宿主目标驱动器调 Stage-B 懒展开。
 * 任何失败（无 key/LLM 错/坏 JSON/空/不可执行）都回调空表；上层据此回落单遍 decompose
 * （§10：目标不被吞），或在懒边界进 §9 恢复。{@link #parse} 纯静态可单测。
 */
final class RddStagePlanner {
    private static final Gson GSON = new Gson();

    private RddStagePlanner() {}

    /** 主入口：异步规划一级清单，回调收到可用的 PrimarySpec 列表（失败=空表）。 */
    static void planStages(UUID companionId, String objective, Consumer<List<PrimarySpec>> done) {
        // 注入"当前真实背包"：规划师要站在已有资产上推进，不倒退重规划已持有的装备/设施。
        // 再贴上经验知识（无知识时与原来逐字相同）。
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        String base = RddPlanningKnowledge.attach(
                RddRiskPlanning.prepHint(objective, snapshot.availableCounts())
                        + planningPrompt(objective, snapshot, RddPlugin.planningAssets(companionId)),
                RddPlanningPolicy.block(objective, "stage_a"));
        String userContent = RddPlanningKnowledge.withKnowledge(RddPlanningKnowledge.HOST, companionId,
                base, objective, "stage_a", List.of());
        RddDecomposer.llmAsk(companionId, "stage_a", userContent, PLAN_SYSTEM, PLAN_TOOL,
                args -> {
                    RddPlanGuard.Stages guarded = RddPlanGuard.filterStages(parse(args), objective);
                    // P2.2：高风险阶段自动补 wait_for 硬门（提前备装，由依赖门强制）
                    List<PrimarySpec> planned = RddRiskPlanning.injectWaitFor(guarded.allowed(), snapshot.availableCounts());
                    RddPlanningKnowledge.publishPolicy(companionId, "stage_a",
                            RddPlanningPolicy.appliedRules(objective, "stage_a"),
                            guarded.dropped(), List.of());
                    done.accept(planned);
                },
                () -> done.accept(List.of()));
    }

    /**
     * 容错解析 plan_stages 参数 JSON → PrimarySpec 列表。
     * 阶段缺主题丢弃；wait_for 资产键不可执行（裸键/占位/大写）或 minimum 为负 → 整条门丢弃
     * （阶段保留、无门也合法，跨级资产门是增强不是必须）。解析彻底失败返回空表。
     */
    static List<PrimarySpec> parse(String argumentsJson) {
        List<PrimarySpec> out = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return out;
        }
        try {
            JsonObject root = GSON.fromJson(argumentsJson, JsonObject.class);
            if (root == null || !root.has("stages") || !root.get("stages").isJsonArray()) {
                return out;
            }
            JsonArray arr = root.getAsJsonArray("stages");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                String theme = o.has("theme") ? o.get("theme").getAsString() : null;
                if (theme == null || theme.isBlank()) {
                    continue;
                }
                out.add(new PrimarySpec(theme.strip(), parseWaitFor(o.get("wait_for"))));
                if (out.size() >= RddChainFactory.MAX_STAGES) {
                    break;
                }
            }
        } catch (RuntimeException ex) {
            return List.of();
        }
        return out;
    }

    /** wait_for 可空；逐条取可用(资产键形状) + 非负 minimum（缺省 1），坏条丢弃整条门不进列表。 */
    private static List<AssetRequirement> parseWaitFor(JsonElement wfEl) {
        if (wfEl == null || !wfEl.isJsonArray()) {
            return List.of();
        }
        List<AssetRequirement> wf = new ArrayList<>();
        for (JsonElement el : wfEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String key = o.has("asset_key") ? o.get("asset_key").getAsString() : null;
            if (!RddKeys.usable(key)) {
                continue;
            }
            int minimum = 1;
            if (o.has("minimum")) {
                int m = o.get("minimum").getAsInt();
                if (m <= 0) {
                    continue; // 非法数量：整条门不要，别拿 m=0/-1 当"无需持有"
                }
                minimum = m;
            }
            wf.add(new AssetRequirement(key, minimum));
        }
        return wf;
    }

    // ---------- prompt 与合成工具 ----------

    private static final String PLAN_SYSTEM =
            "你是 MC 女仆的目标规划师。把主人的整条目标规划成 2~12 个按顺序推进、串起来能完成整条目标的一级发展阶段（阶段/phase）。"
                    + "每个阶段 = 一条该阶段要达成的发展主线（如\"石器时代：做石斧石镐、备好基础材料\"），"
                    + "不是单个物品，也不是整条战役。阶段按依赖顺序排列，前面的阶段达成后才能进入下一个。"
                    + "若某阶段开工前背包必须先持有某物品，在 wait_for 给出真实 minecraft 命名空间 ID"
                    + "（如 minecraft:stone_pickaxe）和 minimum；无法确定的跨级前置不要硬写。"
                    + "只输出 plan_stages 工具调用，不要写多余文字。";

    private static final String PLAN_BODY_TAIL =
            "请用 plan_stages 工具给出发展阶段清单。每个阶段包含：\n"
                    + "- theme：该阶段要达成的发展主线（可读、自含，能被据此拆出可执行的子步骤）\n"
                    + "- wait_for（可选）：进入该阶段前必须已持有的前置资产，{asset_key: 真实命名空间ID, minimum: 数量}\n"
                    + "阶段间不要遗漏：从现状一路推到主人目标完成。";

    static String planningPrompt(String objective, Map<String, Integer> held) {
        return planningPrompt(objective, held, "");
    }

    static String planningPrompt(String objective, Map<String, Integer> held, String worldAssets) {
        return composePlanningPrompt(objective, renderHeldAssets(held), worldAssets);
    }

    /** P1.5：Planner 直接吃统一资产快照（含"已失去/不确定"显式告知）。 */
    static String planningPrompt(String objective, PlanningAssetSnapshot snapshot, String worldAssets) {
        return composePlanningPrompt(objective, renderHeldAssets(snapshot), worldAssets);
    }

    private static String composePlanningPrompt(String objective, String heldBlock, String worldAssets) {
        return "主人的目标：" + objective + "\n\n"
                + heldBlock
                + (worldAssets == null || worldAssets.isBlank() ? "" : worldAssets + "\n\n")
                + PLAN_BODY_TAIL;
    }

    /** 背包上下文块：空背包返回空串（不写"空"误导 LLM），非空按 key 排序保证输出稳定。 */
    private static String renderHeldAssets(Map<String, Integer> held) {
        if (held == null || held.isEmpty()) {
            return "";
        }
        String items = new TreeMap<>(held).entrySet().stream()
                .map(e -> "- " + e.getKey() + " ×" + e.getValue())
                .collect(Collectors.joining("\n"));
        return "【你当前已真实持有（背包扫描）：】\n" + items + "\n\n"
                + "【规划铁律】站在\"已经拥有上面这些\"继续推进：已持有的装备/工具/设施（如 wooden_pickaxe、crafting_table）"
                + "不要在任何阶段重复规划重新获取；theme 从现状往下一时代推进，不倒退。"
                + "只有后续会被消耗掉的东西（食物、合成/烧炼原料）才按需要规划补量。\n\n";
    }

    /** P1.5：来自统一快照的背包块——可持有 + 显式告知"已失去/不确定"。 */
    private static String renderHeldAssets(PlanningAssetSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        if (snap != null && !snap.availableCounts().isEmpty()) {
            String items = snap.availableCounts().entrySet().stream()
                    .map(e -> "- " + e.getKey() + " ×" + e.getValue())
                    .collect(Collectors.joining("\n"));
            sb.append("【你当前已真实持有（背包扫描）：】\n").append(items).append("\n\n")
                    .append("【规划铁律】站在\"已经拥有上面这些\"继续推进：已持有的装备/工具/设施（如 wooden_pickaxe、crafting_table）")
                    .append("不要在任何阶段重复规划重新获取；theme 从现状往下一时代推进，不倒退。")
                    .append("只有后续会被消耗掉的东西（食物、合成/烧炼原料）才按需要规划补量。\n\n");
        }
        if (snap != null && !snap.lostIds().isEmpty()) {
            sb.append("【已失去（死亡/掉落判定失效，绝不要假设还持有，需要就重新获取）：】")
                    .append(String.join("、", snap.lostIds())).append("\n\n");
        }
        if (snap != null && !snap.unknownIds().isEmpty()) {
            sb.append("【状态不确定（先核实再依赖）：】")
                    .append(String.join("、", snap.unknownIds())).append("\n\n");
        }
        return sb.toString();
    }

    /** 合成工具：LLM 直接以结构化 JSON 返回一级清单（引擎无 response_format:json_object）。 */
    private static final IToolSpec PLAN_TOOL = new IToolSpec() {
        @Override public String name() { return "plan_stages"; }
        @Override public String description() {
            return "把主人的整条目标规划成按顺序推进的多个一级发展阶段（主题，不含具体子步骤）。"
                    + "每个阶段的 theme 描述该阶段要达成什么；可选的 wait_for 给进入该阶段前必须已持有的资产"
                    + "（asset_key 必须是真实 minecraft 命名空间 ID）。";
        }
        @Override public Map<String, Object> parameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "stages", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "theme", Map.of("type", "string",
                                                            "description", "该阶段要达成的发展主线（可读、自含）"),
                                                    "wait_for", Map.of(
                                                            "type", "array",
                                                            "description", "进入该阶段前必须已持有的前置资产（可选）",
                                                            "items", Map.of(
                                                                    "type", "object",
                                                                    "properties", Map.of(
                                                                            "asset_key", Map.of("type", "string",
                                                                                    "description", "真实 minecraft 命名空间 ID，如 minecraft:stone_pickaxe"),
                                                                            "minimum", Map.of("type", "integer",
                                                                                    "description", "最少持有数量，缺省 1")),
                                                                    "required", List.of("asset_key")))),
                                            "required", List.of("theme")))),
                    "required", List.of("stages"));
        }
    };
}
