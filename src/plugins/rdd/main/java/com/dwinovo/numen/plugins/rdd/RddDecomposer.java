package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.LlmObservation;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.rdd.api.BodyInstruction;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 客户端侧的 LLM 目标分解器：把 {@code /goal} 的自然语言目标拆成多个带真实资产条件
 * （+可选身体指令）的 HARD_CODED 二级目标。
 *
 * <p>只在 GoalSinks sink（客户端主线程）调用；HTTP 在传输线程跑，结果经
 * {@code Minecraft.getInstance().execute} 弹回主线程。任何失败/无 key/坏 JSON
 * 都回落 {@link RddChainFactory#fromObjective} 占位链——目标绝不被吞。
 *
 * <p>{@link #parse} 是纯静态容错解析，可独立单测。
 */
final class RddDecomposer {
    private static final Logger LOG = LoggerFactory.getLogger(RddDecomposer.class);
    private static final Gson GSON = new Gson();

    private RddDecomposer() {}

    /**
     * 共享 LLM 单轮 + 单合成工具 传输（客户端主线程）。成功把首个工具调用的 arguments JSON
     * 交回调；任何失败（无 key/LLM 错/未调工具）交 onFail。两段都在 {@code Minecraft.getInstance().execute}
     * 弹回主线程——调用方据此决定回落或走 §9 失败恢复，绝不在传输层伪造结果。
     */
    static void llmAsk(UUID companionId, String stage, String userContent, String system, IToolSpec tool,
                       Consumer<String> onArguments, Runnable onFail) {
        INumenConfig cfg = Services.CONFIG;
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            Minecraft.getInstance().execute(onFail);
            return;
        }
        LlmEndpoint ep = new LlmEndpoint(cfg.getProvider(), cfg.getModel(), cfg.getApiKey(),
                cfg.getBaseUrl(), cfg.getProxy(), "auto");
        RddPlugin.publishPlanningContext(companionId, stage, userContent, system, tool);
        NumenLlmClient.forEndpoint(ep)
                .chatStreaming(List.of(new ConvoState.Msg.User(userContent)),
                        List.of(tool), system, null,
                        new LlmObservation("supervisor", companionId.toString(), stage, RddMonitor::publish))
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        onFail.run();
                        return;
                    }
                    AssistantTurn turn = result.turn();
                    if (turn == null || !turn.hasToolCalls()) {
                        onFail.run();
                        return;
                    }
                    LlmToolCall call = turn.toolCalls().get(0);
                    onArguments.accept(call.arguments());
                }));
    }

    /** 主入口：异步分解目标，回调收到一个可用的 Goal（成功=分解链，失败=占位链）。 */
    static void decompose(UUID companionId, String objective, Consumer<Goal> done) {
        INumenConfig cfg = Services.CONFIG;
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            LOG.warn("[rdd] 未配置 LLM key，跳过分解，回落占位链");
            Minecraft.getInstance().execute(() -> done.accept(RddChainFactory.fromObjective(companionId, objective)));
            return;
        }
        LlmEndpoint ep = new LlmEndpoint(cfg.getProvider(), cfg.getModel(), cfg.getApiKey(),
                cfg.getBaseUrl(), cfg.getProxy(), "auto");
        // 经验知识贴进最终请求正文（无知识时与原来逐字相同）
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        String userContent = RddPlanningKnowledge.withKnowledge(RddPlanningKnowledge.HOST, companionId,
                RddRiskPlanning.prepHint(objective, snapshot.availableCounts())
                        + decompositionPrompt(objective, snapshot, List.of(),
                                RddPlugin.planningAssets(companionId)),
                objective, "fallback", List.of());
        RddPlugin.publishPlanningContext(companionId, "fallback", userContent, SYSTEM_PROMPT, DECOMPOSE_TOOL);
        NumenLlmClient.forEndpoint(ep)
                .chatStreaming(List.of(new ConvoState.Msg.User(userContent)),
                        List.of(DECOMPOSE_TOOL), SYSTEM_PROMPT, null,
                        new LlmObservation("supervisor", companionId.toString(), "fallback", RddMonitor::publish))
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (error != null) {
                        LOG.warn("[rdd] 分解 LLM 调用失败，回落占位链: {}", error.toString());
                        done.accept(RddChainFactory.fromObjective(companionId, objective));
                        return;
                    }
                    AssistantTurn turn = result.turn();
                    if (!turn.hasToolCalls()) {
                        LOG.warn("[rdd] LLM 未调用分解工具，回落占位链");
                        done.accept(RddChainFactory.fromObjective(companionId, objective));
                        return;
                    }
                    LlmToolCall call = turn.toolCalls().get(0);
                    // 守门：硬规则由代码执行，模型不听也要拦住（未要求的可选升级、已持有的资产）
                    RddPlanGuard.Filtered guarded = RddPlanGuard.filterSubtasks(
                            parse(call.arguments()), objective, snapshot.availableCounts());
                    RddPlanningKnowledge.publishPolicy(companionId, "fallback",
                            RddPlanningPolicy.appliedRules(objective, "fallback"),
                            guarded.dropped(), guarded.reused());
                    List<SubtaskSpec> specs = guarded.allowed();
                    if (specs.isEmpty()) {
                        LOG.warn("[rdd] 分解结果不可用，回落占位链");
                        done.accept(RddChainFactory.fromObjective(companionId, objective));
                        return;
                    }
                    try {
                        done.accept(RddChainFactory.fromSpec(companionId, objective, specs));
                    } catch (RuntimeException ex) {
                        LOG.warn("[rdd] 分解规格非法，回落占位链: {}", ex.toString());
                        done.accept(RddChainFactory.fromObjective(companionId, objective));
                    }
                }));
    }

    /**
     * Stage-B：把"当前一级主题"当迷你目标懒展开成二级规格列表（spec §9：进入一级前必须生成
     * ≥1 个合法可执行二级）。任何失败（无 key/LLM 错/坏 JSON/解析为空）回调<b>空表</b>——
     * 成功与否由宿主目标驱动器判定并走 §9 恢复，绝不在此回落占位假二级（asset_key="goal"
     * 这类不可执行节点不得冒充"展开成功"）。
     */
    static void decomposeSpecs(UUID companionId, String themeObjective, int attempt,
                               List<String> completedStages, Consumer<List<SubtaskSpec>> done) {
        decomposeSpecsWithHint(companionId, themeObjective, attempt, completedStages, "", done);
    }

    /**
     * Stage-B 带额外提示重载（P4 重规划用）：把重规划上下文（失败原因/真实资产/完成事实/风险缺口）
     * 追加进提示词，让本次重分解站在真实状态上换计划。
     */
    static void decomposeSpecsWithHint(UUID companionId, String themeObjective, int attempt,
                                       List<String> completedStages, String extraHint,
                                       Consumer<List<SubtaskSpec>> done) {
        // §9：重试带上下文修正——再次尝试时把"为何上次不可执行"喂回去，逼 LLM 给可检测的真实物品键。
        String hint = attempt >= 1
                ? "\n注意：上一次生成的子步骤被判定不可执行——每个 asset_key 必须是完整的小写命名空间 ID"
                + "（如 minecraft:oak_log），condition 必须有真实可检测物品，minimum 给具体数字，body 可选。"
                + "不要裸键/占位符/大写，不要用 \"goal\" 冒充物品。宁可少拆，不可拆出跑不动的步骤。"
                : "";
        // 上一次被判定不可执行 → 把该失败事实一并交给知识侧，召回「这类失败该怎么办」的经验
        List<String> knownFailures = attempt >= 1
                ? List.of("上一次生成的子步骤被判定不可执行")
                : List.of();
        PlanningAssetSnapshot snapshot = RddPlugin.planningSnapshot(companionId);
        String base = RddPlanningKnowledge.attach(
                RddRiskPlanning.prepHint(themeObjective, snapshot.availableCounts())
                        + decompositionPrompt(themeObjective, snapshot,
                        completedStages == null ? List.of() : completedStages,
                        RddPlugin.planningAssets(companionId)) + hint
                        + (extraHint == null || extraHint.isBlank() ? "" : "\n\n" + extraHint),
                RddPlanningPolicy.block(themeObjective, "stage_b"));
        String userContent = RddPlanningKnowledge.withKnowledge(RddPlanningKnowledge.HOST, companionId,
                base, themeObjective, "stage_b", knownFailures);
        RddDecomposer.llmAsk(companionId, "stage_b", userContent,
                SYSTEM_PROMPT, DECOMPOSE_TOOL,
                args -> {
                    RddPlanGuard.Filtered guarded = RddPlanGuard.filterSubtasks(parse(args), themeObjective,
                            snapshot.availableCounts());
                    RddPlanningKnowledge.publishPolicy(companionId, "stage_b",
                            RddPlanningPolicy.appliedRules(themeObjective, "stage_b"),
                            guarded.dropped(), guarded.reused());
                    done.accept(guarded.allowed());
                },
                () -> done.accept(List.of()));
    }

    /**
     * 容错解析 LLM 的 {@code decompose_goal} 参数 JSON → 规格列表。
     * 坏 JSON / 缺 subtasks / 字段缺失 / 资产键不可执行 / 超上限，一律丢弃对应条目；解析彻底失败返回空列表
     * （调用方据此回落占位链或走 §9 失败恢复）。不对称错误代价：宁可失败，不可拿残缺链冒充真分解。
     */
    static List<SubtaskSpec> parse(String argumentsJson) {
        List<SubtaskSpec> out = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return out;
        }
        try {
            JsonObject root = GSON.fromJson(argumentsJson, JsonObject.class);
            if (root == null || !root.has("subtasks") || !root.get("subtasks").isJsonArray()) {
                return out;
            }
            JsonArray arr = root.getAsJsonArray("subtasks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                SubtaskSpec spec = parseOne(el.getAsJsonObject());
                if (spec != null) {
                    out.add(spec);
                    if (out.size() >= RddChainFactory.MAX_SUBTASKS) {
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 分解 JSON 解析失败: {}", ex.toString());
            return List.of();
        }
        return out;
    }

    /** 单条规格：description 非空、condition 含 asset_key（+可选非负 minimum）；body 可选。非法返回 null。 */
    private static SubtaskSpec parseOne(JsonObject o) {
        String description = o.has("description") ? o.get("description").getAsString() : null;
        if (description == null || description.isBlank()) {
            return null;
        }
        JsonObject cond = o.has("condition") && o.get("condition").isJsonObject()
                ? o.getAsJsonObject("condition") : null;
        if (cond == null) {
            return null;
        }
        if (cond.has("type") && !"inventory".equals(cond.get("type").getAsString())) {
            Map<String, Object> fact = new LinkedHashMap<>();
            for (var entry : cond.entrySet()) fact.put(entry.getKey(), toPlain(entry.getValue()));
            return com.dwinovo.numen.rdd.core.WorldFactConditions.valid(fact)
                    ? new SubtaskSpec(description, fact, parseBody(o.get("body"))) : null;
        }
        String assetKey = cond.has("asset_key") ? cond.get("asset_key").getAsString() : null;
        // 资产键形状不可执行(裸键/占位如 goal/大写) 永不匹配背包键 -> 判不可用丢弃，
        // 宁缺毋滥，不让"合法 JSON 但跑不动"的死条件进任务链造成假卡死。
        String group = cond.has("group") ? cond.get("group").getAsString() : null;
        if (group != null ? !com.dwinovo.numen.rdd.core.InventoryGroups.known(group) || assetKey != null : !RddKeys.usable(assetKey)) {
            return null;
        }
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(group == null ? "asset_key" : "group", group == null ? assetKey : group);
        if (cond.has("minimum")) {
            int minimum = cond.get("minimum").getAsInt();
            if (minimum < 0 || group != null && minimum == 0
                    || cond.get("minimum").getAsDouble() != minimum) {
                return null;
            }
            condition.put("minimum", minimum);
        }
        if (group != null && !condition.containsKey("minimum")) condition.put("minimum", 1);
        if (cond.has("optional") && cond.get("optional").isJsonPrimitive()
                && cond.getAsJsonPrimitive("optional").isBoolean())
            condition.put("optional", cond.get("optional").getAsBoolean());
        return new SubtaskSpec(description, condition, parseBody(o.get("body")));
    }

    /** body 可选；task_type 非空才认，否则视为检测-only。 */
    private static BodyInstruction parseBody(JsonElement bodyEl) {
        if (bodyEl == null || !bodyEl.isJsonObject()) {
            return null;
        }
        JsonObject b = bodyEl.getAsJsonObject();
        String taskType = b.has("task_type") ? b.get("task_type").getAsString() : null;
        if (taskType == null || taskType.isBlank()) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (b.has("args") && b.get("args").isJsonObject()) {
            for (var entry : b.getAsJsonObject("args").entrySet()) {
                args.put(entry.getKey(), toPlain(entry.getValue()));
            }
        }
        return new BodyInstruction(taskType, args);
    }

    /** Gson JsonElement → 纯 JVM 值（String/Number/Boolean/null/Map/List）。 */
    private static Object toPlain(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                double value = p.getAsDouble();
                if (value == (int) value) return (int) value;
                return value;
            }
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            return p.getAsString();
        }
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var entry : e.getAsJsonObject().entrySet()) {
                m.put(entry.getKey(), toPlain(entry.getValue()));
            }
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement el : e.getAsJsonArray()) {
                l.add(toPlain(el));
            }
            return l;
        }
        return e.toString();
    }

    // ---------- prompt 与合成工具 ----------

    private static final String SYSTEM_PROMPT =
            "你是 MC 女仆的目标分解器。把主人的目标拆成 1~8 个具体、按依赖顺序排列、可逐步检测的子步骤。"
                    + "每个子步骤的 condition 必须用真实的 minecraft 物品命名空间 ID（如 minecraft:oak_log、"
                    + "minecraft:iron_ingot），数量 minimum 给具体数字。能交给身体执行的一步带上 body 工具调用。"
                    + "通用食物/木头/垫脚方块需求优先用 condition:{group:food|wood|blocks,minimum:数量}，组内按物品总数相加，禁止同时给asset_key。"
                    + "food只计直接可食用的安全食物，不计小麦/干草/生土豆；wood原木和木板按件计，不换算配方。指定合成原料仍用asset_key。"
                    + "世界条件可用type=advancement(advancement ID)、entity_killed(entity ID,minimum)、structure(structure ID,可选dimension)、base(可选dimension)。"
                    + "base必须本人已绑定可重生的床，附近已放置箱子和熔炉；背包设施不算建成。structure须本人到达已加载结构，击杀须本人统计，不以实体消失当击败。"
                    + "没有对应验收器的部分不要伪造物品替代。只输出 decompose_goal 工具调用，不要写多余文字。";

    static String decompositionPrompt(String objective, Map<String, Integer> held,
                                      List<String> completedStages) {
        return decompositionPrompt(objective, held, completedStages, "");
    }

    static String decompositionPrompt(String objective, Map<String, Integer> held,
                                      List<String> completedStages, String worldAssets) {
        return composeDecompositionPrompt(objective, completedStages, renderHeldAssets(held), worldAssets);
    }

    /** P1.5：Planner 直接吃统一资产快照（含"已失去/不确定"显式告知）。 */
    static String decompositionPrompt(String objective, PlanningAssetSnapshot snapshot,
                                      List<String> completedStages, String worldAssets) {
        return composeDecompositionPrompt(objective, completedStages, renderHeldAssets(snapshot), worldAssets);
    }

    private static String composeDecompositionPrompt(String objective, List<String> completedStages,
                                                      String heldBlock, String worldAssets) {
        return "主人的目标：" + objective + "\n\n"
                + renderCompletedStages(completedStages)
                + heldBlock
                + (worldAssets == null || worldAssets.isBlank() ? "" : worldAssets + "\n\n")
                + "请用 decompose_goal 工具给出子步骤。每个子步骤包含：\n"
                + "- description：这一步要做什么\n"
                + "- condition：{asset_key: 物品命名空间ID, minimum: 需要数量}\n"
                + "  或 {group: food|wood|blocks, minimum: 组内最低总数}；不要强求食物品种齐全。\n"
                + "  世界事实：{type:base}；{type:advancement,advancement:minecraft:story/...}；"
                + "{type:structure,structure:minecraft:stronghold}；{type:entity_killed,entity:minecraft:ender_dragon,minimum:1}。\n"
                + "- body（可选）：把这一步直接交给女仆身体执行。task_type 只能从下面 4 个里选，"
                + "args 统一用 {item, count}（item=物品/矿石命名空间ID，count=要拿到的数量，尽量与 condition 对齐）：\n"
                + "    mine：挖矿/采集方块（如 {item: \"minecraft:iron_ore\", count: 3}，可给 minecraft:raw_iron）\n"
                + "    craft：合成（如 {item: \"minecraft:iron_pickaxe\", count: 1}）\n"
                + "    equip_item：装备（如 {item: \"minecraft:iron_pickaxe\"}）\n"
                + "    collect_items：捡起附近掉落物（可选 {item} 限定）\n"
                + "    严禁用这 4 个之外的工具名——不存在的名字不会被执行，只会拖慢推进。\n";
    }

    /** 已完成前置阶段上下文块（空则返回空串）。 */
    private static String renderCompletedStages(List<String> completed) {
        if (completed == null || completed.isEmpty()) {
            return "";
        }
        return "【已达成的前置阶段】" + String.join(" → ", completed) + "\n\n";
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
                + "【拆解铁律】站在\"已经拥有上面这些\"继续推进：已持有的装备/工具/设施"
                + "（如 wooden_pickaxe、crafting_table、stone_axe）不要再拆出\"重新获取/再做一把\"的子步骤，"
                + "除非后面马上要消耗它。只规划把当前资产推进到本阶段目标还缺的部分；"
                + "会消耗掉的（食物、合成/烧炼原料如 plank/stick/木炭）才按本阶段真实消耗补量。\n\n";
    }

    /** P1.5：来自统一快照的背包块——可持有 + 显式告知"已失去/不确定"。 */
    private static String renderHeldAssets(PlanningAssetSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        if (snap != null && !snap.availableCounts().isEmpty()) {
            String items = snap.availableCounts().entrySet().stream()
                    .map(e -> "- " + e.getKey() + " ×" + e.getValue())
                    .collect(Collectors.joining("\n"));
            sb.append("【你当前已真实持有（背包扫描）：】\n").append(items).append("\n\n")
                    .append("【拆解铁律】站在\"已经拥有上面这些\"继续推进：已持有的装备/工具/设施")
                    .append("（如 wooden_pickaxe、crafting_table、stone_axe）不要再拆出\"重新获取/再做一把\"的子步骤，")
                    .append("除非后面马上要消耗它。只规划把当前资产推进到本阶段目标还缺的部分；")
                    .append("会消耗掉的（食物、合成/烧炼原料如 plank/stick/木炭）才按本阶段真实消耗补量。\n\n");
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

    /** 合成工具：让 LLM 直接以结构化 JSON 返回分解结果（引擎没有 response_format:json_object）。 */
    private static final IToolSpec DECOMPOSE_TOOL = new IToolSpec() {
        @Override public String name() { return "decompose_goal"; }
        @Override public String description() {
            return "把主人的目标拆成可逐步检测、可逐步执行的子步骤列表。"
                    + "每个子步骤必须能被世界状态确定性判定：condition 里给 asset_key（minecraft 物品命名空间 ID）"
                    + "和 minimum（数量）。可选的 body 指定把这步交给哪个身体工具执行（task_type + args）。";
        }
        @Override public Map<String, Object> parameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "subtasks", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "description", Map.of("type", "string",
                                                            "description", "这一步做什么（给主人的可读说明）"),
                                                    "condition", Map.of(
                                                            "type", "object",
                                                            "description", "确定性完成条件",
                                                            "properties", Map.of(
                                                                    "type", Map.of("type", "string", "enum", List.of("inventory", "advancement", "structure", "entity_killed", "base")),
                                                                    "advancement", Map.of("type", "string"),
                                                                    "structure", Map.of("type", "string"),
                                                                    "entity", Map.of("type", "string"),
                                                                    "dimension", Map.of("type", "string"),
                                                                    "asset_key", Map.of("type", "string",
                                                                            "description", "minecraft 物品命名空间ID，如 minecraft:oak_log"),
                                                                    "group", Map.of("type", "string", "enum", List.of("food", "wood", "blocks")),
                                                                    "optional", Map.of("type", "boolean", "description", "仅明确可省略的补充食物标true"),
                                                                    "minimum", Map.of("type", "integer",
                                                                            "description", "需要的最少数量")),
                                                            "required", List.of()),
                                                    "body", Map.of(
                                                            "type", "object",
                                                            "description", "可选的交给身体执行的指令",
                                                            "properties", Map.of(
                                                                    "task_type", Map.of("type", "string",
                                                                            "description", "身体工具名，仅限 4 个: mine(挖矿)/craft(合成)/equip_item(装备)/collect_items(捡掉落)。不要用其他名字——不存在的名字不会执行。"),
                                                                    "args", Map.of("type", "object",
                                                                            "description", "工具参数，统一用 {item, count}（item=物品/矿石命名空间ID, count=数量）。")),
                                                            "required", List.of("task_type")))),
                                    "required", List.of("description", "condition"))),
                    "required", List.of("subtasks"));
        }
    };
}
