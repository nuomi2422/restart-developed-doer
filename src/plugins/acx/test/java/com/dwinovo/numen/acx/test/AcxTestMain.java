package com.dwinovo.numen.acx.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.acx.api.AcxCondition;
import com.dwinovo.numen.acx.api.AcxExperience;
import com.dwinovo.numen.acx.api.AcxPrecondition;
import com.dwinovo.numen.acx.api.AcxLifecycleState;
import com.dwinovo.numen.acx.api.Operator;
import com.dwinovo.numen.acx.core.AcxParamBinder;
import com.dwinovo.numen.acx.core.AcxQualityLedger;
import com.dwinovo.numen.acx.core.AcxRunStats;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStep;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolSchema;
import com.dwinovo.numen.acx.api.AcxVerifier;
import com.dwinovo.numen.acx.core.AcxCatalog;
import com.dwinovo.numen.acx.core.AcxConditionEvaluator;
import com.dwinovo.numen.acx.core.AcxFingerprint;
import com.dwinovo.numen.acx.core.AcxLimits;
import com.dwinovo.numen.acx.core.AcxLoader;
import com.dwinovo.numen.acx.core.AcxProgress;
import com.dwinovo.numen.acx.core.AcxRuntimeLimits;
import com.dwinovo.numen.acx.core.AcxRunner;
import com.dwinovo.numen.acx.core.AcxValueResolver;
import com.dwinovo.numen.acx.core.FileAcxLibrary;
import com.dwinovo.numen.acx.core.JsonlRecordStore;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.core.AcxAliases;
import com.dwinovo.numen.acx.core.AcxArtifactAdopter;
import com.dwinovo.numen.acx.core.AcxHostBridge;
import com.dwinovo.numen.acx.core.NumenToolCatalog;
import com.dwinovo.numen.acx.core.PortToolAdapter;

import static com.dwinovo.numen.acx.test.T.contains;
import static com.dwinovo.numen.acx.test.T.eq;
import static com.dwinovo.numen.acx.test.T.isTrue;
import static com.dwinovo.numen.acx.test.T.notContains;
import static com.dwinovo.numen.acx.test.T.notNull;
import static com.dwinovo.numen.acx.test.T.throwsA;
import com.dwinovo.numen.acx.api.AcxGameThreadGate;
import com.dwinovo.numen.acx.core.AcxFacade;
import com.dwinovo.numen.acx.core.AcxGatedTool;
import com.dwinovo.numen.acx.core.AcxSessionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACX 全部离线单测。纯 JDK，不需要 Minecraft、不需要 gradle。
 *
 * <p>覆盖重点是「与 DD 的五处差异」和「我们自己已登记的洞」，
 * 每个回归测试名里写明它挡住的是哪个坑。</p>
 */
public final class AcxTestMain {

    public static void main(String[] args) {
        linear();
        references();
        conditions();
        ifBlock();
        whileThreeTerminals();
        ddBreakpointBug();
        resumeContract();
        breakers();
        cancelAndVerifier();
        ignoreFailure();
        subAc();
        fingerprint();
        progress();
        loader();
        library();
        records();
        realAcLib();
        numenPorts();
        preconditionsAndBindings();
        qualityAndLifecycle();
        measuredNumenLib();
        intArrayPort();
        facadeKeyAlias();
        takeDiag();
        varsAndWriteback();
        calcAndLoop();
        forBlock();
        ctrlSnapshot();
        facadeHardening();
        subAcPublishing();
        versionStoreRoundTrip();
        hostFacade();
        learnerDraftAdoption();
        System.exit(T.summary());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 17. 真实 ac-lib 目录当夹具（保证交付的 .ac 真能加载、真能跑）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 积木注册表，输出字段按 DD 的<b>实测</b>值给（见 docs/06-积木输出字段实测对照表.md）。
     *
     * <p>故意用实测值而不是 DD 手写的 SCHEMA_OVERRIDES —— 那张表 11 个里过期 6 个，
     * scan_blocks 漏了 done / count / nearest_hostile_*，用它做夹具会把正确的 .ac 判成错。</p>
     */
    private static Fake.Registry realBlocks() {
        return new Fake.Registry()
                .add(Fake.fixed("scan_blocks", Fake.params(
                        "done", true, "count", 2, "targets", List.of(),
                        "target_block", "iron_ore",
                        "target_x", 1, "target_y", 2, "target_z", 3,
                        "target_absX", 1, "target_absY", 2, "target_absZ", 3,
                        "nearest_hostile", "zombie", "nearest_hostile_type", "zombie",
                        "nearest_hostile_x", 1, "nearest_hostile_y", 2,
                        "nearest_hostile_z", 3, "nearest_hostile_dist", 4)))
                .add(Fake.fixed("navigate_to", Fake.params(
                        "targetX", 1.0, "targetY", 2.0, "targetZ", 3.0,
                        "distance", 1.0, "actual_distance", 1.0, "relative", false)))
                .add(Fake.fixed("mine_block", Fake.params(
                        "blockX", 1, "blockY", 2, "blockZ", 3, "blockId", "iron_ore",
                        "hardness", 3.0, "distance", 1.5)))
                .add(Fake.fixed("mine_path_to", Fake.params("count", 3, "path", List.of())))
                .add(Fake.fixed("safety_check", Fake.params(
                        "safe", true, "x", 1, "y", 2, "z", 3, "reason", "ok")))
                .add(Fake.fixed("check_inventory", Fake.params(
                        "items", List.of(), "found", true, "count", 1)))
                .add(Fake.fixed("pickup_items", Fake.params("gathered", 2, "count", 2)))
                .add(Fake.fixed("smelt_batch", Fake.params("smelted", 3, "count", 3)))
                .add(Fake.fixed("smelt_item", Fake.params("smelted", 1, "count", 1)))
                .add(Fake.fixed("craft_item", Fake.params("item", "iron_pickaxe", "count", 1)))
                .add(Fake.fixed("knowledge_note", Fake.params("noted", true)))
                .add(Fake.fixed("use_shield", Fake.params("blocked", true, "duration_sec", 2)))
                .add(Fake.fixed("attack_entity", Fake.params("killed", 1, "count", 1)))
                .add(Fake.fixed("set_combat_mode", Fake.params("enabled", true)))
                .add(Fake.fixed("give_pickaxe", Fake.params("given", true)))
                .add(Fake.fixed("make_furnace", Fake.params("placed", true)))
                .add(Fake.fixed("make_iron_armor", Fake.params("worn", true, "count", 4)))
                .add(Fake.fixed("wait_ticks", Fake.params("waited", 1)));
    }

    /** 找 ac-lib 目录（测试的工作目录不固定） */
    private static Path acLib() {
        for (String c : new String[]{"ac-lib", "../ac-lib", "ac-upgrade/ac-lib",
                "../../ac-upgrade/ac-lib"}) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

private static void realAcLib() {
        T.group("17 真实 ac-lib 目录（交付物自检 · 按 Numen 真形状写）");

        Path lib = acLib();
        if (lib == null) {
            T.test("找得到 ac-lib 目录", () -> {
                throw new AssertionError("找不到 ac-lib（工作目录=" + Path.of(".").toAbsolutePath() + "）");
            });
            return;
        }

        AcxLoader.LoadReport rep = AcxLoader.loadAll(lib, numenRegistry(), false, true);

        T.test("ac-lib 全部 .ac 零错误加载（strict=true）", () -> {
            eq(0, rep.errors().size(), "错误: " + rep.errors());
        });

        T.test("ac-lib 零字段引用 warning（$ref 都指向真机实测过的输出字段）", () -> {
            eq(0, rep.warnings().size(), "warning: " + rep.warnings());
        });

        T.test("12 个 stable 全注册、0 个 beta", () -> {
            // 2026-10-09：11 → 12，新增 lure_animals_into_pen（引羊 v6：按 type 筛羊 + 三趟线性）。
            // 总数是硬断言，涨了必须显式改这里——这正是它存在的意义（覆盖度变化不许静默通过）。
            eq(12, rep.registered().size(), "stable 数: " + rep.registered().keySet());
            eq(0, rep.betaOnly().size(), "beta 数: " + rep.betaOnly().keySet());
            T.isTrue(rep.registered().containsKey("ore_scan_inspect"), "实测形状脚本在");
            T.isTrue(rep.registered().containsKey("timeout_demo"), "按 AC 限额脚本在");
            T.isTrue(rep.registered().containsKey("subac_nesting"), "子 AC 脚本在");
            T.isTrue(rep.registered().containsKey("lure_animals_into_pen"), "引羊脚本在");
        });

        T.test("★ DD 原件一个都不注册（形状与 Numen 真工具不兼容，已降级到 reference/）", () -> {
            for (String n : new String[]{"defend_self_v2", "guarded_ore_survey", "iron_armor_surface_v2",
                    "mine_smelt_craft_iron_pickaxe", "ore_survey", "survey_iron", "survey_diamond",
                    "mine_ore_wallbreak"}) {
                T.isTrue(!rep.registered().containsKey(n), n + " 不该被加载");
            }
            T.isTrue(Files.exists(lib.resolve("reference").resolve("defend_self_v2.ac")),
                    "原件文件仍在 reference/ 留档");
        });

        T.test("planner_notes / safety_notes 真的被读进来了", () -> {
            String n = rep.registered().get("ore_scan_inspect").plannerNotes();
            T.isTrue(n != null && !n.isBlank(), "planner_notes 非空: " + n);
            String s = rep.registered().get("timeout_demo").safetyNotes();
            T.isTrue(s != null && !s.isBlank(), "safety_notes 非空: " + s);
        });

        T.test("DD 的两份重名脚本在 ACX 下会被拒（这正是移植时要改的原因）", () -> {
            Path d = tmpDir();
            // defend_self.ac 的结构：顶层 scan + while 内又来一个 scan
            write(d.resolve("dup.ac"), """
                    {"name":"dup","steps":[
                      {"id":"scan","block":"scan_blocks","params":{"block_ids":["minecraft:stone"]}},
                      {"id":"loop","block":"while","params":{
                         "condition":{"field":"$scan.matches.0.block","op":"==","value":"minecraft:iron_ore"}},
                       "children":[
                         {"id":"scan","block":"scan_blocks","params":{"block_ids":["minecraft:iron_ore"]}}
                       ]}
                    ]}
                    """);
            AcxLoader.LoadReport r2 = AcxLoader.loadAll(d, realBlocks(), false, true);
            eq(0, r2.registered().size(), "重名 id 不注册");
            T.isTrue(r2.errors().stream().anyMatch(e -> e.contains("重复")),
                    "错误要说清是 id 重复: " + r2.errors());
        });
    }
    private static AcxDefinition def(AcxStep... steps) {
        return defN("t", steps);
    }

    /**
     * 带名字的 AC 定义。
     *
     * <p>必须给嵌套 AC 不同名字：执行器的循环引用检测按 <b>AC 名</b>判（callStack 存名字），
     * 两个不同定义同名会在第一层就误判成自递归。加载器按名字去重，所以同名定义
     * 本来也进不了目录 —— 这是契约，不是巧合。</p>
     */
    private static AcxDefinition defN(String name, AcxStep... steps) {
        return new AcxDefinition(name, "1", "", List.of(), List.of(steps), "", "");
    }

    private static AcxStep step(String id, String block, Map<String, Object> params) {
        return new AcxStep(id, block, params);
    }

    private static AcxStep ctrl(String id, String block, Map<String, Object> params, AcxStep... children) {
        return new AcxStep(id, block, params, List.of(children));
    }

    private static AcxRunner.Builder runner(Fake.Registry reg) {
        return AcxRunner.builder().tools(reg);
    }

    // ── 1. 线性 ─────────────────────────────────────────────────────

    private static void linear() {
        T.group("1 线性执行");

        T.test("线性成功 → SUCCESS 且断点推进到末尾", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("a", Fake.params("x", 1)))
                    .add(Fake.fixed("b", Fake.params("y", 2)));
            AcxRunRecord r = runner(reg).build().run(def(
                    step("s1", "a", Map.of()),
                    step("s2", "b", Map.of())), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(2, r.completedStepIndex(), "断点");
            eq("t", r.acName(), "AC 名");
            eq(1, Fake.calls.count("a"), "a 调用次数");
        });

        T.test("未知积木 → FAIL 且 failedStep 有值", () -> {
            Fake.Registry reg = new Fake.Registry();
            AcxRunRecord r = runner(reg).build().run(def(step("s1", "nope", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            eq("s1", r.failedStep(), "失败步");
            contains(r.errorMessage(), "不存在的积木或 AC", "错误消息");
        });

        T.test("积木抛 Error(非 RuntimeException) 也被兜住 → FAIL", () -> {
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.throwing("boom", new NoClassDefFoundError("net/minecraft/X")));
            AcxRunRecord r = runner(reg).build().run(def(step("s1", "boom", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "执行异常", "错误消息");
        });

        T.test("积木返回 null → FAIL（不 NPE）", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.nullReturning("nullret"));
            AcxRunRecord r = runner(reg).build().run(def(step("s1", "nullret", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "积木契约违规", "错误消息");
        });
    }

    // ── 2. 变量引用 ─────────────────────────────────────────────────

    private static void references() {
        T.group("2 $prev / $stepId / $input 引用");

        T.test("三种引用都能解析", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("target_x", 10, "done", true)))
                    .add(Fake.fixed("nav", Fake.params("moved", 1)));
            AcxRunRecord r = runner(reg).build().run(def(
                    step("scan", "scan", Map.of()),
                    step("nav", "nav", Fake.params(
                            "x", "$scan.target_x",
                            "prev_flag", "$prev.done",
                            "want", "$input.count"))),
                    Map.of("count", 3));
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            Map<String, Object> seen = Fake.calls.lastParams("nav");
            eq(10, seen.get("x"), "$scan.target_x");
            eq(true, seen.get("prev_flag"), "$prev.done");
            eq(3, seen.get("want"), "$input.count");
        });

        T.test("引用解析不到 → 保留原字符串，不抛异常", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("a", Map.of()));
            AcxRunRecord r = runner(reg).build().run(def(
                    step("a", "a", Fake.params("x", "$nosuch.field"))), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq("$nosuch.field", Fake.calls.lastParams("a").get("x"), "保留原字串");
        });

        T.test("toDouble 对未解析引用返回 0（让 while 至少进一次，DD 原行为）", () -> {
            eq(0.0, AcxValueResolver.toDouble("$prev.x"), "未解析引用");
            eq(3.0, AcxValueResolver.toDouble("3"), "数字字符串");
            eq(3.0, AcxValueResolver.toDouble(3), "数字");
            eq(0.0, AcxValueResolver.toDouble(null), "null");
        });
    }

    // ── 3. 条件求值 ─────────────────────────────────────────────────

    private static void conditions() {
        T.group("3 条件三路比较");

        Map<String, Object> out = Fake.params("n", 5, "flag", true, "s", "abc");
        Map<String, Object> empty = Map.of();

        T.test("数值：< <= > >= == !=", () -> {
            eq(true, ev("$prev.n", "<", 6, out, empty), "5<6");
            eq(false, ev("$prev.n", "<", 5, out, empty), "5<5");
            eq(true, ev("$prev.n", ">=", 5, out, empty), "5>=5");
            eq(false, ev("$prev.n", ">", 9, out, empty), "5>9");
            eq(true, ev("$prev.n", "!=", 4, out, empty), "5!=4");
        });

        T.test("布尔：只支持 == / !=", () -> {
            eq(true, ev("$prev.flag", "==", true, out, empty), "true==true");
            eq(false, ev("$prev.flag", "==", false, out, empty), "true==false");
            // 布尔上 '<' 恒 false（DD switch default）
            eq(false, ev("$prev.flag", "<", true, out, empty), "布尔不支持 <");
        });

        T.test("字符串：只支持 == / !=", () -> {
            eq(true, ev("$prev.s", "==", "abc", out, empty), "abc==abc");
            eq(false, ev("$prev.s", "<", "abd", out, empty), "字符串不支持 <");
        });

        T.test("字符串 \"true\" 与布尔 true 可互比（真实 .ac 里就有这么写的）", () -> {
            eq(true, ev("$prev.flag", "==", "true", out, empty), "true==\"true\"");
        });

        T.test("未知算子 → 恒 false（不能静默当 EQ）", () -> {
            AcxCondition c = new AcxCondition("$prev.n",
                    com.dwinovo.numen.acx.api.Operator.fromSymbol("约等于") == null
                            ? com.dwinovo.numen.acx.api.Operator.UNKNOWN
                            : com.dwinovo.numen.acx.api.Operator.EQ,
                    5);
            eq(false, AcxConditionEvaluator.evaluate(c, out, empty, Map.of()), "未知算子");
        });

        T.test("describeUnmatched 给出「实际 vs 期望」", () -> {
            AcxCondition c = new AcxCondition("$prev.n",
                    com.dwinovo.numen.acx.api.Operator.EQ, 99);
            String s = AcxConditionEvaluator.describeUnmatched(c, out, empty, Map.of());
            contains(s, "if无匹配", "前缀");
            contains(s, "实际 5", "实际值");
            contains(s, "期望 99", "期望值");
        });
    }

    private static boolean ev(String field, String op, Object value,
                              Map<String, Object> last, Map<String, Object> in) {
        var o = com.dwinovo.numen.acx.api.Operator.fromSymbol(op);
        return AcxConditionEvaluator.evaluate(new AcxCondition(field, o, value), last, in, Map.of());
    }

    // ── 4. if ──────────────────────────────────────────────────────

    private static void ifBlock() {
        T.group("4 if 控制块");

        T.test("条件成立 → 执行 children", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("done", true)))
                    .add(Fake.fixed("act", Map.of()));
            AcxRunRecord r = runner(reg).build().run(def(
                    step("scan", "scan", Map.of()),
                    ctrl("gate", "if", Fake.params("condition", Fake.cond("$prev.done", "==", true)),
                            step("act", "act", Map.of()))), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(1, Fake.calls.count("act"), "children 应执行");
            eq(2, r.completedStepIndex(), "if 正常走完算完成");
        });

        T.test("条件不成立 → SUCCESS + _if_unmatched 诊断 + IF_UNMATCHED 事件", () -> {
            Fake.resetCalls();
            Fake.Events evs = new Fake.Events();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("done", false)))
                    .add(Fake.fixed("act", Map.of()));
            AcxRunRecord r = runner(reg).events(evs).build().run(def(
                    step("scan", "scan", Map.of()),
                    ctrl("gate", "if", Fake.params("condition", Fake.cond("$prev.done", "==", true)),
                            step("act", "act", Map.of()))), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态（跳过不是失败）");
            eq(0, Fake.calls.count("act"), "children 不该执行");
            T.isTrue(evs.has(AcxEvent.Kind.IF_UNMATCHED), "应发 IF_UNMATCHED 事件");
        });

        T.test("if 套 if 递归生效", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("a", true, "b", true)))
                    .add(Fake.fixed("deep", Map.of()));
            runner(reg).build().run(def(
                    step("scan", "scan", Map.of()),
                    ctrl("o1", "if", Fake.params("condition", Fake.cond("$prev.a", "==", true)),
                            ctrl("o2", "if", Fake.params("condition", Fake.cond("$scan.b", "==", true)),
                                    step("deep", "deep", Map.of())))), Map.of());
            eq(1, Fake.calls.count("deep"), "嵌套 if 应执行");
        });

        T.test("控制块 children 为空 → FAIL", () -> {
            Fake.Registry reg = new Fake.Registry();
            AcxRunRecord r = runner(reg).build().run(def(
                    new AcxStep("g", "if", Fake.params("condition", Fake.cond("a", "==", 1)), List.of())),
                    Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "缺少 children", "错误消息");
        });
    }

    // ── 5. while 三终点 ─────────────────────────────────────────────

    private static void whileThreeTerminals() {
        T.group("5 while 循环三终点（2026-08-04 铁律）");

        T.test("终点1 条件转假 → SUCCESS + _loop_count", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 3)),
                    step("c", "c", Map.of())));
            AcxRunRecord r = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(3, Fake.calls.count("c"), "刚好跑 3 轮");
            eq(1, r.completedStepIndex(), "断点推进过 while");
        });

        T.test("终点2 连续无进展 → PAUSED + _stagnated（不是 FAIL）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.zero("z"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.mined", "<", 99),
                                "stagnant_limit", 3),
                    step("z", "z", Map.of())));
            AcxRunRecord r = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.PAUSED, r.status(), "状态");
            eq("loop", r.stagnantStep(), "stagnantStep");
            contains(r.pausedReason(), "无实质进展", "pausedReason");
            contains(r.errorMessage(), "【暂停可续】", "人类可读消息");
            contains(r.errorMessage(), "AI诊断", "给 AI 的诊断提示");
        });

        T.test("终点3 max_iters 硬上限 → PAUSED + _reached_max_iters", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 999),
                                "max_iters", 5, "stagnant_limit", 99),
                    step("c", "c", Map.of())));
            AcxRunRecord r = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.PAUSED, r.status(), "状态");
            eq(5, Fake.calls.count("c"), "跑满 max_iters");
            contains(r.pausedReason(), "max_iters=5", "pausedReason");
        });

        T.test("停滞阈值可配：stagnant_limit=1 立刻停", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.zero("z"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.mined", "<", 99),
                                "stagnant_limit", 1),
                    step("z", "z", Map.of())));
            runner(reg).build().run(d, Map.of());
            eq(1, Fake.calls.count("z"), "一轮就判停滞");
        });

        T.test("有进展就重置停滞计数（不会误杀正常循环）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 7),
                                "stagnant_limit", 2),
                    step("c", "c", Map.of())));
            AcxRunRecord r = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "一直有进展应正常收敛");
            eq(7, Fake.calls.count("c"), "跑满条件轮数");
        });

        T.test("默认阈值就是 20 / 3（DD 原值）", () -> {
            eq(20, AcxLimits.DEFAULT_MAX_ITERS, "max_iters");
            eq(3, AcxLimits.DEFAULT_STAGNANT_LIMIT, "stagnant_limit");
            eq(200, AcxLimits.DEFAULT_MAX_STEPS, "max_steps");
            eq(30_000L, AcxLimits.DEFAULT_MAX_TIMEOUT_MS, "max_timeout_ms");
        });

        doWhile();
    }

    // ── 5b. do_while：DD 靠「重名 step id 后写覆盖」实现的那条语义 ──

    private static void doWhile() {
        T.group("5b do_while（DD 隐式契约的显式化）");

T.test("do_while：条件源在 body 里才产生 → 第 1 轮也照跑", () -> {
            // 条件引用的 stepId 只在 body 里被赋值。默认（轮首求值）下引用解析不到，
            // 条件恒假 → while 一次都不跑。DD 靠「顶层与 body 内两个 scan 同名，
            // allOutputs.put 后写覆盖」绕过去 —— ACX 拒绝重名 id，所以要显式 do_while。
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("probe", Fake.params("done", true)));
            AcxRunRecord r = runner(reg).build().run(def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$probe.done", "==", true),
                            "max_iters", 4, "stagnant_limit", 99, "do_while", true),
                    step("probe", "probe", Map.of()))), Map.of());
            // 跑满 max_iters=4 → 终点3 → PAUSED（不是 FAIL），断点停在 while 之前
            eq(AcxStatus.PAUSED, r.status(), "跑满 max_iters 是 PAUSED 不是 FAIL");
            eq(4, Fake.calls.count("probe"), "★ do_while 下条件源在 body 里也跑满轮数");
            eq(0, r.completedStepIndex(), "断点没推进，留给 resume");
        });

        T.test("不开 do_while：同样的脚本 while 一次都不跑（这正是 DD 的隐式依赖）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("probe", Fake.params("done", true)));
            AcxRunRecord r = runner(reg).build().run(def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$probe.done", "==", true),
                            "max_iters", 4),
                    step("probe", "probe", Map.of()))), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "不失败，只是没跑");
            eq(0, Fake.calls.count("probe"), "★ 轮首求值时读不到 body 里才产生的字段");
        });

        T.test("do_while 第 2 轮起条件真生效：body 把 done 翻成 false 就收手", () -> {
            Fake.resetCalls();
            int[] n = {0};
            AcxTool flip = new AcxTool() {
                @Override public String name() { return "probe"; }
                @Override public AcxToolSchema schema() {
                    return new AcxToolSchema(Map.of("done", AcxToolSchema.ParamType.BOOLEAN), java.util.Set.of());
                }
                @Override public AcxStepOutcome execute(Map<String, Object> p, AcxCallContext c) {
                    n[0]++;
                    return AcxStepOutcome.success(Fake.params("done", n[0] < 3));
                }
            };
            Fake.Registry reg = new Fake.Registry().add(flip);
            AcxRunRecord r = runner(reg).build().run(def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$probe.done", "==", true),
                            "max_iters", 10, "stagnant_limit", 99, "do_while", true),
                    step("probe", "probe", Map.of()))), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "成功");
            eq(3, n[0], "★ 跑 3 轮：前两轮 done=true，第 3 轮翻 false 后收手");
        });

        T.test("do_while 也认字符串 \"true\"（真实 .ac 里两种写法都有）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("probe", Fake.params("done", true)));
            runner(reg).build().run(def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$probe.done", "==", true),
                            "max_iters", 2, "stagnant_limit", 99, "do_while", "true"),
                    step("probe", "probe", Map.of()))), Map.of());
            eq(2, Fake.calls.count("probe"), "字符串 \"true\" 也要被认");
        });

        T.test("do_while 不影响 if（if 永远只跑一次）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("probe", Fake.params("done", true)))
                    .add(Fake.fixed("body", Fake.params("mined", 1)));
            runner(reg).build().run(def(
                    step("seed", "probe", Map.of()),
                    ctrl("g", "if",
                            Fake.params("condition", Fake.cond("$prev.done", "==", true),
                                    "do_while", true),
                            step("body", "body", Map.of()))), Map.of());
            eq(1, Fake.calls.count("body"), "if 不因 do_while 变成循环");
        });
    }

    // ── 6. DD 的断点洞（回归） ──────────────────────────────────────

    private static void ddBreakpointBug() {
        T.group("6 DD 断点洞回归（AcRunner.java:193-200 + :223）");

        T.test("停滞的 while 在中间步骤 → 后续步骤不执行，断点停在 while", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.zero("z"))
                    .add(Fake.fixed("after", Map.of("placed", 1)));
            AcxDefinition d = def(
                    step("before", "z", Map.of()),
                    ctrl("loop", "while",
                            Fake.params("condition", Fake.cond("$prev.mined", "<", 99),
                                        "stagnant_limit", 1),
                            step("z", "z", Map.of())),
                    step("after", "after", Map.of()));
            AcxRunRecord r = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.PAUSED, r.status(), "状态");
            eq(0, Fake.calls.count("after"), "★ DD 会继续跑 after 并把断点推到末尾 → resume 空跑");
            eq(1, r.completedStepIndex(), "★ 断点必须停在 while 的索引 1");
            eq("loop", r.currentStepId(), "当前步");
        });

        T.test("上面的记录 resume 后能接着跑完（DD 做不到）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.zero("z"))
                    .add(Fake.fixed("after", Map.of("placed", 1)));
            AcxDefinition d = def(
                    step("before", "z", Map.of()),
                    ctrl("loop", "while",
                            Fake.params("condition", Fake.cond("$prev.mined", "<", 3),
                                        "stagnant_limit", 1),
                            step("z", "z", Map.of())),
                    step("after", "after", Map.of()));
            AcxRunner runner = runner(reg).build();
            AcxRunRecord first = runner.run(d, Map.of());
            eq(AcxStatus.PAUSED, first.status(), "第一次暂停");

            // 第二轮：把 while 修好（有进展）再 resume
            Fake.Registry reg2 = new Fake.Registry()
                    .add(Fake.mining("z"))
                    .add(Fake.fixed("after", Map.of("placed", 1)));
            AcxRunner runner2 = runner(reg2).build();
            AcxRunRecord second = runner2.resume(d, first);
            eq(AcxStatus.SUCCESS, second.status(), "续跑应成功");
            eq(1, Fake.calls.count("after"), "after 被执行了一次");
        });
    }

    // ── 7. resume 契约 ──────────────────────────────────────────────

    private static void resumeContract() {
        T.group("7 断点续跑契约（沿用现有 AC 的安全链）");

        T.test("版本变了 → 拒绝 + RESUME_REJECTED 事件", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("a", Map.of()));
            Fake.Events evs = new Fake.Events();
            AcxRunner r = runner(reg).events(evs).build();
            AcxDefinition v1 = new AcxDefinition("t", "1", "", List.of(), List.of(step("s", "a", Map.of())), "", "");
            AcxDefinition v2 = new AcxDefinition("t", "2", "", List.of(), List.of(step("s", "a", Map.of())), "", "");
            AcxRunRecord prior = r.run(v1, Map.of());
            var e = throwsA(IllegalArgumentException.class, () -> r.resume(v2, prior));
            contains(e.getMessage(), "版本已变", "拒绝理由");
            T.isTrue(evs.has(AcxEvent.Kind.RESUME_REJECTED), "应发 RESUME_REJECTED");
        });

        T.test("内容变了（同名同版本但步骤不同）→ 指纹不符，拒绝", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("a", Map.of()));
            AcxRunner r = runner(reg).build();
            AcxDefinition v1 = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "a", Map.of())), "", "");
            AcxDefinition v2 = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "a", Fake.params("x", 1))), "", "");
            AcxRunRecord prior = r.run(v1, Map.of());
            var e = throwsA(IllegalArgumentException.class, () -> r.resume(v2, prior));
            contains(e.getMessage(), "内容已变", "拒绝理由");
        });

        T.test("AC 名不匹配 → 拒绝", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("a", Map.of()));
            AcxRunner r = runner(reg).build();
            AcxRunRecord prior = r.run(new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "a", Map.of())), "", ""), Map.of());
            var e = throwsA(IllegalArgumentException.class, () -> r.resume(
                    new AcxDefinition("other", "1", "", List.of(),
                            List.of(step("s", "a", Map.of())), "", ""), prior));
            contains(e.getMessage(), "AC 名不匹配", "拒绝理由");
        });

        T.test("无记录 → 拒绝", () -> {
            Fake.Registry reg = new Fake.Registry();
            AcxRunner r = runner(reg).build();
            throwsA(IllegalArgumentException.class, () -> r.resume(def(step("s", "a", Map.of())), null));
        });

        T.test("合法 resume 发 RESUME_STARTED", () -> {
            Fake.resetCalls();
            Fake.PauseOnce once = new Fake.PauseOnce();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.pauserOnce("gate", once, "等材料"))
                    .add(Fake.fixed("good", Map.of()));
            Fake.Events evs = new Fake.Events();
            AcxRunner r = runner(reg).events(evs).build();
            AcxDefinition d = def(step("s1", "gate", Map.of()), step("s2", "good", Map.of()));
            AcxRunRecord prior = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, prior.status(), "第一次暂停在第 0 步");
            eq(0, prior.completedStepIndex(), "断点在 0");
            AcxRunRecord resumed = r.resume(d, prior);
            eq(AcxStatus.SUCCESS, resumed.status(), "续跑成功");
            T.isTrue(evs.has(AcxEvent.Kind.RESUME_STARTED), "应发 RESUME_STARTED");
        });

        // ★★ 记录里没有指纹（AC-B21，2026-10-06 已修）：null 不等于「指纹一致」
        //   （原缺陷）AcxRunner.checkResumable 写的是 `prior.fingerprint() != null && !fp.equals(...)`
        //   ⇒ 指纹为 null 时**整道门被跳过**，而名字与版本仍然通过
        //   ⇒ resume 拿着一份「内容可能早已改过」的旧断点静默续跑。
        //   语义：**验不了 ≠ 验过了**。修法：null 直接拒绝（文案点明「记录里没有指纹」）。
        //   现有 resumeContract 只测了非 null 的不匹配，本测试补上 null 反例。
        T.test("记录里没有指纹 → 拒绝续跑（AC-B21，2026-10-06 已修）", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("a", Map.of()));
            AcxRunner r = runner(reg).build();
            AcxDefinition d = def(step("s", "a", Map.of()));
            AcxRunRecord prior = r.run(d, Map.of());
            // ★ AcxRunRecord 只有私有构造器（收 Builder）⇒ 只能走 Builder。
            //   这里刻意只给 runId/name/version/status，其它一律不给 ⇒ 指纹是 null。
            AcxRunRecord noFp = AcxRunRecord.builder()
                    .runId(prior.runId())
                    .acName(d.name())
                    .acVersion(d.version())
                    .status(AcxStatus.PAUSED)
                    .completedStepIndex(0)
                    .timestamp(1L)
                    .build();
            var e = throwsA(IllegalArgumentException.class, () -> r.resume(d, noFp));
            contains(e.getMessage(), "指纹",
                    "★ 报错文案必须点明是「指纹」这一项 ——「没有指纹」与「指纹不一致」处置不同："
                            + "前者可能要迁移旧记录，后者必须重规划");
        });

        // ★★ TODO(AC-B18) 已知红 —— 69 号文档「第 1 组红线」第 4 条：**恢复不能重复发出已受理的动作**
        //
        // 机理（逐行可核）：AcxRunner.java:328-332 只在 `r.isSuccess()` 时 `markStepCompleted(c, i)`；
        // 而 335-338 的 PAUSED 分支**直接 return，不推进已完成步数**。
        // 于是「异步动作已受理、还没做完」的运行会停在**那一步**，
        // 而 resume 的起点 `at = prior.completedStepIndex()`（:248）就指着**同一步** ⇒ 那个动作被再发一次。
        //
        // 后果 = **重复挖、重复走、重复消耗资源**（owner 原话「会不会重复挖、重复走」）。
        //
        // ⚠️ 为什么原有 206 项一条都抓不到它：本组上一条用 `pauserOnce`（只暂停一次），
        //   于是恢复时那一步不再暂停 ⇒ **恰好掩盖了「重发」**；
        //   别的检查测的是拒绝门（版本/内容/名字/无记录），不是「恢复后动作发了几次」。
        //
        // ★ 断言写的是**应该怎样**（只发一次），所以它现在必然红。
        //   按本仓库既有约定（build.gradle 的「N of the checks are deliberately RED」+ TODO 标记），
        //   它被登记进 AcxOfflineSuiteTest.KNOWN_RED。**本轮不修** —— 修它要改 markStepCompleted
        //   的调用时机，那是功能改动，属 owner 冻结的范围。
        T.test("恢复不能重复发已受理的动作（TODO AC-B18，当前会重发）", () -> {
            Fake.resetCalls();
            Fake.PauseOnce once = new Fake.PauseOnce();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.pauserOnce("gate", once, "已受理但未完成"))
                    .add(Fake.fixed("good", Map.of()));
            Fake.Events evs = new Fake.Events();
            AcxRunner r = runner(reg).events(evs).build();
            AcxDefinition d = def(step("s1", "gate", Map.of()), step("s2", "good", Map.of()));
            AcxRunRecord prior = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, prior.status(), "第一次应停在「已受理未完成」那一步");
            eq(1, Fake.calls.count("gate"), "暂停之前那一步只该被发一次");
            AcxRunRecord resumed = r.resume(d, prior);
            eq(AcxStatus.SUCCESS, resumed.status(), "恢复后应跑完");
            eq(1, Fake.calls.count("gate"),
                    "★ 恢复不能重复发出已受理的动作：那一步 run+resume 合起来只该被发一次");
            eq(1, Fake.calls.count("good"), "后半步不该被重复发");
        });
    }

    // ── 8. 熔断 ────────────────────────────────────────────────────

    private static void breakers() {
        T.group("8 三道熔断");

        T.test("总步数上限 → FAIL + CIRCUIT_STEPS 事件", () -> {
            Fake.resetCalls();
            Fake.Events evs = new Fake.Events();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 100000), "max_iters", 100000),
                    step("c", "c", Map.of())));
            AcxRunRecord r = runner(reg).events(evs)
                    .limits(new AcxRuntimeLimits(10, 30_000, 8))
                    .build().run(d, Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "最大总步数", "错误消息");
            T.isTrue(evs.has(AcxEvent.Kind.CIRCUIT_STEPS), "应发 CIRCUIT_STEPS");
        });

        T.test("步数跨递归累计（子 AC 也算）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxRunner r = AcxRunner.builder().tools(reg).build();
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            defs.put("child", defN("child_ac", ctrl("inner", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 999), "max_iters", 999),
                    step("c", "c", Map.of()))));
            AcxDefinition parent = defN("parent", step("p1", "child", Map.of()));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs))
                    .limits(new AcxRuntimeLimits(6, 30_000, 8)).build().run(parent, Map.of());
            eq(AcxStatus.FAIL, rec.status(), "跨递归累计后应熔断");
            contains(rec.errorMessage(), "最大总步数", "错误消息");
            T.isTrue(Fake.calls.count("c") <= 6, "实际执行步数不超过上限，实际 " + Fake.calls.count("c"));
        });

        T.test("wall-clock 超时 → 状态就是 TIMEOUT（不是靠消息里含「熔斷」判）", () -> {
            Fake.resetCalls();
            Fake.Events evs = new Fake.Events();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 99999), "max_iters", 99999),
                    step("c", "c", Map.of())));
            AcxRunRecord r = runner(reg).events(evs)
                    .limits(new AcxRuntimeLimits(1_000_000, 60, 8))
                    .build().run(d, Map.of());
            eq(AcxStatus.TIMEOUT, r.status(), "★ 必须是 TIMEOUT 枚举，不是 FAIL");
            T.isTrue(evs.has(AcxEvent.Kind.CIRCUIT_TIMEOUT), "应发 CIRCUIT_TIMEOUT");
        });

        T.test("循环引用 A→B→A → FAIL + CIRCUIT_RECURSION", () -> {
            Fake.Events evs = new Fake.Events();
            Fake.Registry reg = new Fake.Registry();
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            defs.put("b", def(step("toA", "a", Map.of())));
            defs.put("a", def(step("toB", "b", Map.of())));
            AcxRunRecord r = AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs)).events(evs)
                    .build().run(defs.get("a"), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "循环引用", "错误消息");
            T.isTrue(evs.has(AcxEvent.Kind.CIRCUIT_RECURSION), "应发 CIRCUIT_RECURSION");
        });

        T.test("递归层数超限也拦（防间接无限递归）", () -> {
            Fake.Registry reg = new Fake.Registry();
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            for (int i = 0; i < 12; i++) {
                defs.put("c" + i, defN("c" + i, step("s", "c" + (i + 1), Map.of())));
            }
            defs.put("c12", defN("c12", step("s", "leaf", Map.of())));
            reg.add(Fake.fixed("leaf", Map.of()));
            AcxRunRecord r = AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs))
                    .limits(new AcxRuntimeLimits(1000, 30_000, 3)).build().run(defs.get("c0"), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "递归层数超限", "错误消息");
        });
    }

    // ── 9. 取消 + 实证验证 ──────────────────────────────────────────

    private static void cancelAndVerifier() {
        T.group("9 随时可中断 + 实证验证");

        T.test("取消 → PAUSED 且断点保留（不丢）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.count", "<", 9999), "max_iters", 9999),
                    step("c", "c", Map.of())));
            AcxRunner r = runner(reg).build();
            // run 之后立刻取消：先拿到 runId 才能取消，所以走 cancel(runId) 的另一条路
            AcxRunRecord rec = r.run(d, Map.of());
            T.isTrue(rec.runId() != null, "有 runId");
            T.isTrue(!r.isRunning(rec.runId()), "执行完就不在跑了");
            T.isTrue(!r.cancel(rec.runId()), "对已结束的 run 取消返回 false");
            T.isTrue(!r.cancel("不存在的id"), "对不存在的 run 取消返回 false");
        });

        T.test("取消是协作式的：积木看得见标志，执行器在步边界优雅收手", () -> {
            Fake.resetCalls();
            Fake.Canceller canceller = new Fake.Canceller();
            Map<String, Object> seen = new LinkedHashMap<>();
            Fake.Registry reg = new Fake.Registry().add(Fake.cancelThenCheck("killer", canceller, seen));
            AcxRunner r = runner(reg).build();
            canceller.runner = r;
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.progress", "<", 9999),
                                "stagnant_limit", 9999),
                    step("killer", "killer", Map.of())));
            AcxRunRecord rec = r.run(d, Map.of());

            eq(AcxStatus.PAUSED, rec.status(), "★ 取消后是 PAUSED（断点保留、可 resume），不是 FAIL/TIMEOUT");
            eq("host_cancelled", rec.pausedReason(), "pausedReason 应说明是宿主取消");
            eq(0, rec.completedStepIndex(), "★ 断点留在 while 自己（索引 0），后续不推进");
            eq(true, seen.get("saw_cancel"), "★ 积木在同一次调用里就能看见 isCancelRequested");
            eq(true, seen.get("run_id_matches"), "AcxCallContext 给了 runId");
            eq("killer", seen.get("step_id"), "AcxCallContext 给了 stepId");
        });

        // 上面那条之所以拿到 PAUSED，靠的是 while 的下一次迭代又走了一次 checkCircuit。
        // 换句话说：现有覆盖是被外层循环「顺带」救回来的，主循环自己并不保证这件事。
        // （AC-B19，2026-10-06 已修：成功收下前复查取消标志 → AcxRunner.cancelled 单源出口。）
        T.test("取消后迟到的成功回执不得把这次运行翻回成功（AC-B19，2026-10-06 已修）", () -> {
            Fake.resetCalls();
            Fake.Canceller canceller = new Fake.Canceller();
            Map<String, Object> seen = new LinkedHashMap<>();
            Fake.Registry reg = new Fake.Registry().add(Fake.cancelThenCheck("late", canceller, seen));
            AcxRunner r = runner(reg).build();
            canceller.runner = r;
            // ★ 关键：单步平铺脚本。上一条有 while 包着，取消标志会被下一轮迭代的
            //   checkCircuit 拦下来；这里没有外层循环帮我们再查一次，所以这条测的是
            //   主循环自己在「最后一步」上的真实行为。
            AcxRunRecord rec = r.run(def(step("late", "late", Map.of())), Map.of());

            eq(true, seen.get("saw_cancel"), "前置事实：积木执行期间确实看见了取消标志");
            eq(AcxStatus.PAUSED, rec.status(),
                    "★ 取消之后才到的成功回执不得把这次运行翻回 SUCCESS（实际 " + rec.status() + "）");
            eq("host_cancelled", rec.pausedReason(), "应说明是宿主取消，而不是报告完成");
        });

        T.test("deadline 到了必然停下（TIMEOUT 或协作式 PAUSED，两者都不推进断点）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.spin("s"));
            AcxDefinition d = def(
                    ctrl("loop", "while",
                            Fake.params("condition", Fake.cond("$prev.progress", "<", 99999999),
                                        "max_iters", 99_999_999, "stagnant_limit", 99999999),
                            step("s", "s", Map.of())));
            AcxRunRecord r = runner(reg).limits(new AcxRuntimeLimits(100_000_000, 250, 8))
                    .build().run(d, Map.of());
            // 谁先到（执行器的 checkCircuit 还是积木自己看见标志）取决于时序，
            // 所以这里只断言不变量：一定停下、一定不推进断点。
            T.isTrue(r.status() == AcxStatus.TIMEOUT || r.status() == AcxStatus.PAUSED,
                    "到期后必然停在 TIMEOUT 或 PAUSED，实际 " + r.status());
            eq(0, r.completedStepIndex(), "断点不推进");
            T.isTrue(Fake.calls.count("s") >= 1,
                    "★ 至少跑过一次（不能第一步就被砍掉），实际 " + Fake.calls.count("s"));
        });

        T.test("verifier 判假成功 → FAIL + VERIFY_FAILED", () -> {
            Fake.resetCalls();
            Fake.Events evs = new Fake.Events();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("mine", Fake.params("mined", 1)));
            AcxVerifier reject = (block, params, claimed) ->
                    AcxVerifier.VerifyResult.reject("世界没变");
            AcxRunRecord r = runner(reg).events(evs).verifier(reject)
                    .build().run(def(step("m", "mine", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "假成功必须判 FAIL");
            contains(r.errorMessage(), "实证验证不通过", "错误消息");
            contains(r.errorMessage(), "世界没变", "带 verifier 的理由");
            T.isTrue(evs.has(AcxEvent.Kind.VERIFY_FAILED), "应发 VERIFY_FAILED");
        });

        T.test("verifier 通过 → SUCCESS", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("mine", Fake.params("mined", 1)));
            AcxRunRecord r = runner(reg).verifier(AcxVerifier.permissive())
                    .build().run(def(step("m", "mine", Map.of())), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
        });

        T.test("verifier 抛异常 → 当场拒绝（不当假成功放行）", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("mine", Fake.params("mined", 1)));
            AcxVerifier boom = (b, p, o) -> {
                throw new IllegalStateException("读世界炸了");
            };
            AcxRunRecord r = runner(reg).verifier(boom)
                    .build().run(def(step("m", "mine", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
            contains(r.errorMessage(), "读世界炸了", "带异常信息");
        });

        T.test("没有 verifier → 放行但只发一次 VERIFIER_ABSENT（不假装验过）", () -> {
            Fake.resetCalls();
            Fake.Events evs = new Fake.Events();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("a", Map.of()))
                    .add(Fake.fixed("b", Map.of()))
                    .add(Fake.fixed("c", Map.of()));
            AcxRunRecord r = runner(reg).events(evs).build().run(def(
                    step("s1", "a", Map.of()),
                    step("s2", "b", Map.of()),
                    step("s3", "c", Map.of())), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(1, evs.count(AcxEvent.Kind.VERIFIER_ABSENT), "★ 只发一次，不是每步一次");
        });
    }

    // ── 10. ignore_failure ──────────────────────────────────────────

    private static void ignoreFailure() {
        T.group("10 ignore_failure「一次到位」策略");

        T.test("顶层失败被忽略 → 继续下一步 + 四个失败诊断字段", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.failing("bad", "没有图纸"))
                    .add(Fake.fixed("next", Map.of("ok", true)));
            AcxRunRecord r = runner(reg).build().run(def(
                    step("b", "bad", Fake.params("ignore_failure", true)),
                    step("n", "next", Fake.params("check", "$prev.failure_reason"))), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(1, Fake.calls.count("next"), "后续步骤应执行");
            Map<String, Object> seen = Fake.calls.lastParams("next");
            eq("没有图纸", seen.get("check"), "$prev.failure_reason 可用");
        });

        T.test("四个诊断字段齐全（供 if($prev.failure_reason) 走降级分支）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.failing("bad", "炸了"))
                    .add(Fake.fixed("probe", Map.of()));
            runner(reg).build().run(def(
                    step("b", "bad", Fake.params("ignore_failure", true)),
                    step("p", "probe", Fake.params(
                            "r", "$prev.failure_reason",
                            "s", "$prev.failed_step",
                            "bl", "$prev.failed_block",
                            "f", "$prev._failed"))), Map.of());
            Map<String, Object> seen = Fake.calls.lastParams("probe");
            eq("炸了", seen.get("r"), "failure_reason");
            eq("b", seen.get("s"), "failed_step");
            eq("bad", seen.get("bl"), "failed_block");
            eq(true, seen.get("f"), "_failed");
        });

        T.test("没声明 ignore_failure → 整条 FAIL", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.failing("bad", "炸"));
            AcxRunRecord r = runner(reg).build().run(def(step("b", "bad", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "状态");
        });

        T.test("while 内失败被忽略 → 拉黑目标 + 跳出本轮换目标继续", () -> {
            Fake.resetCalls();
            Fake.Blacklist bl = new Fake.Blacklist();
            // 第 1 次 mine 失败，之后成功；scan 每轮换坐标
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.counter("scan", "found"))
                    .add(Fake.failing("mine", "这个挖不动"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.found", "<", 2),
                                "stagnant_limit", 99),
                    step("scan", "scan", Map.of()),
                    step("mine", "mine", Fake.params(
                            "x", "$prev.found", "y", 0, "z", 0, "ignore_failure", true))));
            AcxRunRecord r = runner(reg).blacklist(bl).build().run(d, Map.of());
            eq(2, bl.calls.size(), "★ 两个失败目标各拉黑一次");
            eq(1, bl.calls.get(0).get("x"), "拉黑的是已解析后的坐标");
            eq(AcxStatus.SUCCESS, r.status(),
                    "★ 合并失败诊断后 while 能靠条件收敛到终点1（DD 替换语义下只能停滞收场）");
            eq(2, Fake.calls.count("scan"), "scan 换目标继续");
        });

        T.test("拉黑回调的参数是已解析的（不是 $prev 字串）", () -> {
            Fake.resetCalls();
            Fake.Blacklist bl = new Fake.Blacklist();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.counter("scan", "found"))
                    .add(Fake.failing("mine", "挖不动"));
            AcxDefinition d = def(ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.found", "<", 2), "stagnant_limit", 99),
                    step("scan", "scan", Map.of()),
                    step("mine", "mine", Fake.params("x", "$prev.found", "ignore_failure", true))));
            runner(reg).blacklist(bl).build().run(d, Map.of());
            notNull(bl.calls.get(0).get("x"), "x");
            T.isTrue(bl.calls.get(0).get("x") instanceof Number, "x 应是数字而非 $prev 字串");
        });
    }

    // ── 11. 子 AC ──────────────────────────────────────────────────

    private static void subAc() {
        T.group("11 子 AC 递归");

        T.test("step.block 指向另一个 AC → 递归执行并透传参数", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.counter("c", "count"));
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            defs.put("child", defN("child_ac", step("inner", "c", Fake.params("n", "$input.n"))));
            AcxDefinition parent = defN("parent", step("p", "child", Map.of("n", 7)));
            AcxRunRecord r = AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs)).build()
                    .run(parent, Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(1, Fake.calls.count("c"), "子 AC 里的积木应执行");
            eq(7, Fake.calls.lastParams("c").get("n"), "参数透传");
        });

        T.test("子 AC 暂停 → 往上传 PAUSED（DD 会误报 FAIL）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry();
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            defs.put("child", defN("child_ac", ctrl("loop", "while",
                    Fake.params("condition", Fake.cond("$prev.mined", "<", 99), "stagnant_limit", 1),
                    step("z", "z", Map.of()))));
            reg.add(Fake.zero("z"));
            AcxDefinition parent = defN("parent",
                    step("p", "child", Map.of()), step("after", "z", Map.of()));
            AcxRunRecord r = AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs)).build()
                    .run(parent, Map.of());
            eq(AcxStatus.PAUSED, r.status(), "★ 子 AC 的优雅停下不能变成 FAIL");
            eq("p", r.currentStepId(), "停在调用子 AC 的那一步");
        });

        T.test("子 AC 的输出能被父 AC 用 $stepId 引用", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("producer", Fake.params("made", 42)))
                    .add(Fake.fixed("consumer", Map.of()));
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            defs.put("child", defN("child_ac", step("inner", "producer", Map.of())));
            AcxDefinition parent = defN("parent",
                    step("c1", "child", Map.of()),
                    step("c2", "consumer", Fake.params("v", "$c1.made")));
            AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs)).build().run(parent, Map.of());
            eq(42, Fake.calls.lastParams("consumer").get("v"), "★ 跨 AC 的 $stepId 引用");
        });

        T.test("同名时优先按 AC 执行（与 DD 的查找顺序一致）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("amb", Fake.params("from", "block")))
                    .add(Fake.counter("inner_block", "count"));
            Map<String, AcxDefinition> defs = new LinkedHashMap<>();
            // 名为 amb 的 AC 内部只调 inner_block，不自递归
            defs.put("amb", defN("amb", step("s", "inner_block", Map.of())));
            AcxRunRecord r = AcxRunner.builder().tools(reg).catalog(AcxCatalog.of(defs)).build()
                    .run(defN("parent", step("go", "amb", Map.of())), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "走 AC 分支应成功");
            eq(1, Fake.calls.count("inner_block"), "AC 内部执行");
            eq(0, Fake.calls.count("amb"), "同名积木不该被误执行");
        });

        T.test("catalog 里没有同名 AC 时才走积木", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("solo", Fake.params("x", 1)));
            AcxRunRecord r = AcxRunner.builder().tools(reg).build()
                    .run(defN("parent", step("go", "solo", Map.of())), Map.of());
            eq(AcxStatus.SUCCESS, r.status(), "状态");
            eq(1, Fake.calls.count("solo"), "积木被调");
        });
    }

    // ── 12. 指纹 ───────────────────────────────────────────────────

    private static void fingerprint() {
        T.group("12 canonical 指纹");

        T.test("字段顺序不同 → 指纹相同（canonical 的意义）", () -> {
            var a = new AcxDefinition("t", "1", "d", List.of("x", "y"),
                    List.of(step("s", "blk", Fake.params("a", 1, "b", 2))), "", "");
            var b = new AcxDefinition("t", "1", "d", List.of("y", "x"),
                    List.of(step("s", "blk", Fake.params("b", 2, "a", 1))), "", "");
            eq(AcxFingerprint.of(a), AcxFingerprint.of(b), "顺序无关");
        });

        T.test("内容变了 → 指纹不同", () -> {
            var a = new AcxDefinition("t", "1", "", List.of(), List.of(step("s", "blk", Map.of())), "", "");
            var b = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "blk", Fake.params("k", 1))), "", "");
            T.isTrue(!AcxFingerprint.of(a).equals(AcxFingerprint.of(b)), "应不同");
        });

        T.test("嵌套 children 也参与指纹（DD 的 canonical 只处理顶层）", () -> {
            var a = def(ctrl("g", "if", Fake.params("condition", Fake.cond("x", "==", 1)),
                    step("s", "blk", Map.of())));
            var b = def(ctrl("g", "if", Fake.params("condition", Fake.cond("x", "==", 2)),
                    step("s", "blk", Map.of())));
            T.isTrue(!AcxFingerprint.of(a).equals(AcxFingerprint.of(b)), "嵌套也应敏感");
        });

        T.test("数字 1 与 1.0 指纹相同（避免 gson 数字类型抖动造成假变更）", () -> {
            var a = def(step("s", "blk", Fake.params("n", 1)));
            var b = def(step("s", "blk", Fake.params("n", 1.0)));
            eq(AcxFingerprint.of(a), AcxFingerprint.of(b), "数字归一");
        });
    }

    // ── 13. 进展判定 ───────────────────────────────────────────────

    private static void progress() {
        T.group("13 实质进展判定");

        T.test("11 个进度字段任一 > 0 即算有进展", () -> {
            for (String f : AcxProgress.FIELDS) {
                T.isTrue(AcxProgress.hasRealProgress(Fake.params(f, 1)), f + "=1 应算有进展");
            }
            eq(11, AcxProgress.FIELDS.size(), "字段数");
        });

        T.test("0 / null / 空 / 无字段 → 无进展（保守策略）", () -> {
            T.isTrue(!AcxProgress.hasRealProgress(Fake.params("mined", 0)), "0");
            T.isTrue(!AcxProgress.hasRealProgress(Fake.params("mined", null)), "null");
            T.isTrue(!AcxProgress.hasRealProgress(Map.of()), "空");
            T.isTrue(!AcxProgress.hasRealProgress(null), "null map");
            T.isTrue(!AcxProgress.hasRealProgress(Fake.params("other", 5)), "非进度字段");
        });

        T.test("renderSummary 输出 mined=5 形式（配合 extractProgress 用）", () -> {
            eq("mined=5, count=3",
                    AcxProgress.renderSummary(
                            AcxProgress.extractProgress(Fake.params("mined", 5, "count", 3, "junk", 9))),
                    "摘要");
            eq("无", AcxProgress.renderSummary(Map.of()), "空摘要");
        });
    }

    // ── 14. 加载器 ─────────────────────────────────────────────────

    private static void loader() {
        T.group("14 加载器三轮校验 + 分级 + 坏文件隔离");

        T.test("坏文件被跳过，不影响其他 AC", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of("x", 1)));
            Path dir = tmpDir();
            write(dir.resolve("good.ac"), """
                    {"name":"good","steps":[{"id":"s1","block":"blk"}]}
                    """);
            write(dir.resolve("bad.ac"), """
                    {"name":"bad","steps":[]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            eq(1, rep.registered().size(), "只注册 good");
            T.isTrue(rep.registered().containsKey("good"), "good 在");
            T.isTrue(rep.hasErrors(), "应记错误");
        });

        T.test("控制块 children 为空 → 该文件被拒", () -> {
            Fake.Registry reg = new Fake.Registry();
            Path dir = tmpDir();
            write(dir.resolve("noc.ac"), """
                    {"name":"noc","steps":[{"id":"g","block":"if","params":{"condition":{"field":"a","op":"==","value":1}}}]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            eq(0, rep.registered().size(), "不该注册");
            contains(String.join("|", rep.errors()), "非空 children", "错误应提到 children");
        });

        T.test("第2轮：引用不存在的积木 → 该 AC 不注册", () -> {
            Fake.Registry reg = new Fake.Registry();
            Path dir = tmpDir();
            write(dir.resolve("miss.ac"), """
                    {"name":"miss","steps":[{"id":"s","block":"ghost"}]}
                    """);
            write(dir.resolve("ok.ac"), """
                    {"name":"ok","steps":[{"id":"s","block":"real"}]}
                    """);
            reg.add(Fake.fixed("real", Map.of()));
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            eq(1, rep.registered().size(), "只注册引用正确的");
            T.isTrue(rep.registered().containsKey("ok"), "ok 在");
        });

        T.test("第3轮：字段不存在默认只 warning 不阻断（DD 行为）", () -> {
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("target_x", 1)));
            Path dir = tmpDir();
            write(dir.resolve("w.ac"), """
                    {"name":"w","steps":[
                      {"id":"scan","block":"scan"},
                      {"id":"nav","block":"scan","params":{"x":"$prev.nonexistent_field"}}
                    ]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            eq(1, rep.registered().size(), "★ warning 不阻断注册");
            eq(1, rep.warnings().size(), "一条字段 warning");
        });

        T.test("第3轮 strict=true：字段不存在直接拒绝该 AC", () -> {
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("target_x", 1)));
            Path dir = tmpDir();
            write(dir.resolve("w.ac"), """
                    {"name":"w","steps":[
                      {"id":"scan","block":"scan"},
                      {"id":"nav","block":"scan","params":{"x":"$prev.nonexistent_field"}}
                    ]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false, true);
            eq(0, rep.registered().size(), "strict 下不注册");
            eq(0, rep.warnings().size(), "升成 error 不是 warning");
            T.isTrue(rep.errors().stream().anyMatch(e -> e.contains("nonexistent_field")),
                    "错误信息要指名道姓: " + rep.errors());
        });

        T.test("第3轮：引用 AC 内不存在的步骤 id 任何级别都是 error", () -> {
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("target_x", 1)));
            Path dir = tmpDir();
            write(dir.resolve("h.ac"), """
                    {"name":"h","steps":[
                      {"id":"scan","block":"scan"},
                      {"id":"nav","block":"scan","params":{"x":"$nosuchstep.x"}}
                    ]}
                    """);
            // strict=false 也必须拒 —— 这个引用必然是错的，不该只提醒
            AcxLoader.LoadReport loose = AcxLoader.loadAll(dir, reg, false);
            eq(0, loose.registered().size(), "宽松级别也拒");
            T.isTrue(loose.errors().stream().anyMatch(e -> e.contains("nosuchstep")),
                    "错误信息要指名道姓: " + loose.errors());
            AcxLoader.LoadReport strict = AcxLoader.loadAll(dir, reg, false, true);
            eq(0, strict.registered().size(), "strict 一样拒");
        });

        T.test("第3轮：$input.x 不校验（外部输入，静态不可知）", () -> {
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan", Fake.params("target_x", 1)));
            Path dir = tmpDir();
            write(dir.resolve("i.ac"), """
                    {"name":"i","steps":[
                      {"id":"scan","block":"scan"},
                      {"id":"nav","block":"scan","params":{"x":"$input.anything_at_all"}}
                    ]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false, true);
            eq(1, rep.registered().size(), "$input 不该被拦");
            eq(0, rep.warnings().size(), "也不该报 warning");
        });

        T.test("beta 默认不注册，显式 include 才进", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            Path dir = tmpDir();
            write(dir.resolve("beta").resolve("b.beta.ac"), """
                    {"name":"b","steps":[{"id":"s","block":"blk"}]}
                    """);
            AcxLoader.LoadReport off = AcxLoader.loadAll(dir, reg, false);
            eq(0, off.registered().size(), "默认不注册 beta");
            eq(1, off.betaOnly().size(), "但在 betaOnly 名单里");
            AcxLoader.LoadReport on = AcxLoader.loadAll(dir, reg, true);
            eq(1, on.registered().size(), "显式 include 才生效");
        });

        T.test("planner_notes / safety_notes 不再被静默丢弃（DD 丢）", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            Path dir = tmpDir();
            write(dir.resolve("n.ac"), """
                    {"name":"n","description":"d","tags":["t"],
                     "planner_notes":"规划器要看这段","safety_notes":"安全提示",
                     "steps":[{"id":"s","block":"blk"}]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            AcxDefinition d = rep.registered().get("n");
            notNull(d, "应注册");
            eq("规划器要看这段", d.plannerNotes(), "planner_notes 保留");
            eq("安全提示", d.safetyNotes(), "safety_notes 保留");
            eq("1", d.version(), "没写 version 时默认 1");
        });

        T.test("step id 重复 → 拒绝该文件", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            Path dir = tmpDir();
            write(dir.resolve("d.ac"), """
                    {"name":"d","steps":[{"id":"s","block":"blk"},{"id":"s","block":"blk"}]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            eq(0, rep.registered().size(), "重复 id 应拒绝");
        });

        T.test("step 缺少 id / block → 拒绝", () -> {
            Fake.Registry reg = new Fake.Registry();
            Path dir = tmpDir();
            write(dir.resolve("a.ac"), """
                    {"name":"a","steps":[{"block":"x"}]}
                    """);
            write(dir.resolve("b.ac"), """
                    {"name":"b","steps":[{"id":"s"}]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(dir, reg, false);
            eq(0, rep.registered().size(), "两个都该被拒");
            eq(2, rep.errors().size(), "两条错误");
        });
    }

    // ── 15. 版本库（人工批准 + 回滚） ───────────────────────────────

    private static void library() {
        T.group("15 版本库：publish 不上线，approve 才上线，能回滚");

        T.test("publish 只写版本，不切 active", () -> {
            Path f = tmpDir().resolve("lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "初版");
            T.isTrue(!lib.isApproved("t"), "未批准");
            T.isTrue(lib.versions("t").contains("1"), "版本已写入");
            var e = throwsA(IllegalStateException.class, () -> lib.active("t"));
            contains(e.getMessage(), "未人工批准上线", "拒绝理由");
        });

        T.test("approve 后 active 才可用", () -> {
            Path f = tmpDir().resolve("lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "初版");
            lib.approve("t", "1", "user", "验过了");
            T.isTrue(lib.isApproved("t"), "已批准");
            eq("1", lib.activeVersion("t"), "active");
            eq("t", lib.active("t").name(), "能取到定义");
        });

        T.test("静态校验不过 → 拒绝发布且不动已有状态", () -> {
            Path f = tmpDir().resolve("lib.json");
            Fake.Registry reg = new Fake.Registry();
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            throwsA(IllegalArgumentException.class,
                    () -> lib.publish(def(step("s", "ghost", Map.of())), "坏版"));
            T.isTrue(lib.names().isEmpty(), "不该留下任何版本");
        });

        T.test("rollback 切回旧版本", () -> {
            Path f = tmpDir().resolve("lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "v1");
            lib.publish(new AcxDefinition("t", "2", "", List.of(),
                    List.of(step("s", "blk", Fake.params("x", 1))), "", ""), "v2");
            lib.approve("t", "1", "user", "");
            lib.approve("t", "2", "user", "");
            eq("2", lib.activeVersion("t"), "当前 v2");
            lib.rollback("t", "1", "v2 在游戏里炸了");
            eq("1", lib.activeVersion("t"), "回滚到 v1");
            eq(0, lib.active("t").steps().get(0).params().size(), "v1 无参数");
        });

        T.test("落盘后重开库能读回来（含批准信息）", () -> {
            Path f = tmpDir().resolve("lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "初版");
            lib.approve("t", "1", "user", "过了");
            FileAcxLibrary lib2 = new FileAcxLibrary(f, reg);
            lib2.load();
            eq("1", lib2.activeVersion("t"), "active 能读回");
            eq("t", lib2.active("t").name(), "定义能读回");
            eq(1, lib2.active("t").steps().size(), "步骤能读回");
        });

        T.test("回滚到不存在的版本 → 报错并列出可选版本", () -> {
            Path f = tmpDir().resolve("lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "v1");
            var e = throwsA(IllegalArgumentException.class, () -> lib.rollback("t", "9", "瞎写"));
            contains(e.getMessage(), "可回滚的版本", "应列出可选版本");
        });
    }

    // ── 19. 学习者 AC 草稿采纳（B6/S2）────────────────────────────────

    /**
     * 钉的是<b>上线闸门</b>与<b>失败可见</b>两件事：
     * 采纳只 publish 成 GENERATED（绝不 approve），坏草稿要写回 REJECTED + 原因，
     * 重复采纳不产生第二份，已采纳的不再被当成待处理。
     */
    private static void learnerDraftAdoption() {
        T.group("19 学习者 AC 草稿采纳：只进库不上线，坏草稿写回原因");

        T.test("合法草稿 → 进版本库但**未上线**（active 取不到）", () -> {
            Path cfg = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(cfg.resolve("acx/library.json"), reg);
            lib.load();
            AcxArtifactAdopter adopter = new AcxArtifactAdopter(cfg, lib, Set.of());
            writeDraft(cfg, "AC_SCRIPT-c1-m1-abc", "eat_when_hungry",
                    "{\"name\":\"eat_when_hungry\",\"version\":\"1\",\"steps\":[{\"id\":\"s1\",\"block\":\"blk\",\"params\":{}}]}");

            var rep = adopter.adoptAll();
            eq(1, rep.adopted(), "采纳数");
            eq(0, rep.rejected(), "不该有拒收");
            T.isTrue(lib.hasAnyVersion("eat_when_hungry"), "版本已写进库");
            T.isTrue(!lib.isApproved("eat_when_hungry"), "**没有**被批准上线");
            var e = throwsA(IllegalStateException.class, () -> lib.active("eat_when_hungry"));
            contains(e.getMessage(), "未人工批准上线", "拒绝理由要说明白");
        });

        T.test("回填 ADOPTED 并留下 consumer_ref；再扫一次不再重复采纳", () -> {
            Path cfg = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(cfg.resolve("acx/library.json"), reg);
            lib.load();
            AcxArtifactAdopter adopter = new AcxArtifactAdopter(cfg, lib, Set.of());
            writeDraft(cfg, "AC_SCRIPT-c1-m1-def", "eat_when_hungry",
                    "{\"name\":\"eat_when_hungry\",\"version\":\"1\",\"steps\":[{\"id\":\"s1\",\"block\":\"blk\",\"params\":{}}]}");

            eq(1, adopter.adoptAll().adopted(), "首次采纳");
            eq(0, adopter.adoptAll().adopted(), "第二次不该再采纳（幂等）");
            eq("ADOPTED", outboxStatus(cfg, "AC_SCRIPT-c1-m1-def"), "记录状态已回填");
            contains(readOutbox(cfg, "AC_SCRIPT-c1-m1-def"), "eat_when_hungry@1", "回填了可引用标识");
            eq(0, adopter.pendingCount(), "待处理队列已清空");
        });

        T.test("不是 ACX JSON → REJECTED + 原因，原文保留", () -> {
            Path cfg = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(cfg.resolve("acx/library.json"), reg);
            lib.load();
            AcxArtifactAdopter adopter = new AcxArtifactAdopter(cfg, lib, Set.of());
            writeDraft(cfg, "AC_SCRIPT-c1-m2-ghi", "prose", "先看看情况再说");

            var rep = adopter.adoptAll();
            eq(0, rep.adopted(), "散文不该被采纳");
            eq(1, rep.rejected(), "应拒收");
            eq("REJECTED", outboxStatus(cfg, "AC_SCRIPT-c1-m2-ghi"), "写回拒收");
            contains(readOutbox(cfg, "AC_SCRIPT-c1-m2-ghi"), "先看看情况再说", "原文保留等人改");
            T.isTrue(lib.names().isEmpty(), "库里不该留下任何东西");
        });

        T.test("静态校验不过（用了没注册的积木）→ REJECTED 且带明文问题", () -> {
            Path cfg = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(cfg.resolve("acx/library.json"), reg);
            lib.load();
            AcxArtifactAdopter adopter = new AcxArtifactAdopter(cfg, lib, Set.of());
            writeDraft(cfg, "AC_SCRIPT-c1-m3-jkl", "ghost_user",
                    "{\"name\":\"ghost_user\",\"version\":\"1\",\"steps\":[{\"id\":\"s1\",\"block\":\"no_such_block\",\"params\":{}}]}");

            var rep = adopter.adoptAll();
            eq(0, rep.adopted(), "不该采纳");
            eq(1, rep.rejected(), "应拒收");
            String rec = readOutbox(cfg, "AC_SCRIPT-c1-m3-jkl");
            contains(rec, "静态校验未通过", "原因写进记录");
            contains(rec, "no_such_block", "具体是哪个积木有问题");
        });

        T.test("坏文件（截断 JSON）被跳过且不删，不影响同目录其它草稿", () -> {
            Path cfg = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(cfg.resolve("acx/library.json"), reg);
            lib.load();
            AcxArtifactAdopter adopter = new AcxArtifactAdopter(cfg, lib, Set.of());
            Path dir = cfg.resolve("artifact-outbox/AC_SCRIPT/c1");
            write(dir.resolve("broken.json"), "{\"status\": \"PEND");
            writeDraft(cfg, "AC_SCRIPT-c1-m4-mno", "eat_when_hungry",
                    "{\"name\":\"eat_when_hungry\",\"version\":\"1\",\"steps\":[{\"id\":\"s1\",\"block\":\"blk\",\"params\":{}}]}");

            var rep = adopter.adoptAll();
            eq(1, rep.adopted(), "好草稿照常采纳");
            T.isTrue(Files.exists(dir.resolve("broken.json")), "坏文件不许被删");
        });

        T.test("没有投递箱目录 → 0 条，不是异常", () -> {
            Path cfg = tmpDir();
            Fake.Registry reg = new Fake.Registry();
            FileAcxLibrary lib = new FileAcxLibrary(cfg.resolve("acx/library.json"), reg);
            lib.load();
            AcxArtifactAdopter adopter = new AcxArtifactAdopter(cfg, lib, Set.of());
            var rep = adopter.adoptAll();
            eq(0, rep.scanned(), "扫到 0 条");
            eq(0, rep.adopted(), "采纳 0 条");
        });
    }

    /** 按上游契约写一条「学习者已投递」的草稿记录。 */
    private static void writeDraft(Path configDir, String artifactId, String name, String body) {
        write(configDir.resolve("artifact-outbox/AC_SCRIPT/c1").resolve(artifactId + ".json"),
                "{\n"
                        + "  \"artifact_id\": \"" + artifactId + "\",\n"
                        + "  \"kind\": \"AC_SCRIPT\",\n"
                        + "  \"companion_id\": \"c1\",\n"
                        + "  \"review_id\": \"rev-1\",\n"
                        + "  \"memo_id\": \"m-1\",\n"
                        + "  \"name\": \"" + name + "\",\n"
                        + "  \"body\": " + quote(body) + ",\n"
                        + "  \"content_hash\": \"deadbeef\",\n"
                        + "  \"status\": \"PENDING\",\n"
                        + "  \"status_detail\": \"已交出，等下游表态\",\n"
                        + "  \"consumer_ref\": \"\",\n"
                        + "  \"history\": [ { \"at\": \"2026-10-04T00:00:00Z\", \"status\": \"PENDING\", \"detail\": \"submitted\" } ]\n"
                        + "}");
    }

    private static String readOutbox(Path configDir, String artifactId) {
        Path f = configDir.resolve("artifact-outbox/AC_SCRIPT/c1").resolve(artifactId + ".json");
        try {
            return Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 读回投递记录的状态字段：走解析而不是字符串找，避免被 JSON 空格格式绊倒。 */
    private static String outboxStatus(Path configDir, String artifactId) {
        return String.valueOf(JsonlRecordStore.toMap(readOutbox(configDir, artifactId)).get("status"));
    }

    /** 极简 JSON 字符串转义（只处理本测试用到的引号与换行）。 */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ── 16. 记录落盘 ───────────────────────────────────────────────

    private static void records() {
        T.group("16 JSONL 记录落盘");

        T.test("只落 PAUSED/FAIL/TIMEOUT，SUCCESS 不落", () -> {
            Path f = tmpDir().resolve("ac-runs.jsonl");
            Fake.resetCalls();
            try (JsonlRecordStore store = new JsonlRecordStore(f)) {
                Fake.Registry reg = new Fake.Registry()
                        .add(Fake.fixed("ok", Map.of()))
                        .add(Fake.failing("bad", "炸"));
                AcxRunner r = AcxRunner.builder().tools(reg).store(store).build();
                r.run(def(step("s", "ok", Map.of())), Map.of());
                T.isTrue(store.recent().isEmpty(), "SUCCESS 不落");
                r.run(def(step("s", "bad", Map.of())), Map.of());
                eq(1, store.recent().size(), "FAIL 落一条");
            }
            eq(1, countLines(f), "文件里确实只有一行");
        });

        T.test("latestPaused 能捞出断点", () -> {
            Path f = tmpDir().resolve("ac-runs.jsonl");
            Fake.resetCalls();
            try (JsonlRecordStore store = new JsonlRecordStore(f)) {
                Fake.Registry reg = new Fake.Registry().add(Fake.zero("z"));
                AcxRunner r = AcxRunner.builder().tools(reg).store(store).build();
                r.run(def(ctrl("loop", "while",
                        Fake.params("condition", Fake.cond("$prev.mined", "<", 9), "stagnant_limit", 1),
                        step("z", "z", Map.of()))), Map.of());
                Map<String, Object> paused = store.latestPaused();
                notNull(paused, "应能捞到 PAUSED");
                eq("PAUSED", paused.get("status"), "状态");
                eq("loop", paused.get("stagnant_step"), "stagnant_step");
            }
        });

        T.test("落盘失败不影响执行结果（观测不是执行依赖）", () -> {
            Path bad = Path.of("Z:\\根本不存在\\ac-runs.jsonl");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunRecord r = AcxRunner.builder().tools(reg)
                    .store(new JsonlRecordStore(bad))
                    .build().run(def(step("s", "bad", Map.of())), Map.of());
            eq(AcxStatus.FAIL, r.status(), "★ 落盘炸了也要照常返回 FAIL");
        });

        T.test("记录 17 个字段齐全", () -> {
            AcxRunRecord r = new com.dwinovo.numen.acx.api.AcxRunRecord.Builder()
                    .runId("r1").acName("t").acVersion("1").fingerprint("fp")
                    .input(Map.of("k", 1)).status(AcxStatus.PAUSED)
                    .completedStepIndex(2).currentStepId("s3").loopCount(7)
                    .stagnantStep("loop").failedStep(null).errorMessage("m")
                    .pausedReason("p").progress(Fake.params("mined", 3))
                    .elapsedMs(1234).timestamp(99).build();
            Map<String, Object> m = r.toMap();
            for (String k : new String[]{"run_id", "ac_name", "ac_version", "fingerprint",
                    "input", "status", "completed_step_index", "current_step_id", "loop_count",
                    "stagnant_step", "failed_step", "error_message", "paused_reason",
                    "progress", "elapsed_ms", "timestamp"}) {
                T.isTrue(m.containsKey(k), "缺字段 " + k);
            }
            eq("PAUSED", m.get("status"), "状态");
        });
    }

    // ── 小工具 ─────────────────────────────────────────────────────

    private static Path tmpDir() {
        try {
            Path p = Files.createTempDirectory("acx-test-");
            p.toFile().deleteOnExit();
            return p;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int countLines(Path f) {
        try {
            String s = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
            return s.isBlank() ? 0 : s.split("\n").length;
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<String> unused() {
        return new ArrayList<>();
    }

    // ── 18. Numen 端口适配层（AC-B2）────────────────────────────────

    /**
     * 适配层回归。重点钉住四件事：
     * ①「受理 ≠ 完成」—— ACCEPTED 必须落 PAUSED 且可 resume（绝不退化成 SUCCESS）；
     * ② 挂账键 = 顶层AC名|stepId（绝不能用 runId —— resume 每次都会换 runId）；
     * ③ 参数校验 / 控制键剥离在适配层发生（ignore_failure 不下发给宿主）；
     * ④ 工具名与输出字段以 NumenToolCatalog 的实测值为准（含嵌套点路径）。
     */
    private static void numenPorts() {
        T.group("18 Numen 端口适配层（AC-B2）");

        T.test("点路径解析 $prev.position.x 取叶子值，缺字段保留原串", () -> {
            Map<String, Object> pos = Fake.params("x", 1.5, "y", 64, "z", 2.5);
            AcxValueResolver r = new AcxValueResolver(
                    Fake.params("position", pos, "hp", 20), Map.of(), Map.of());
            eq(1.5, r.resolve("$prev.position.x"), "叶子值");
            eq(64, r.resolve("$prev.position.y"), "整数叶子");
            eq(pos, r.resolve("$prev.position"), "整对象仍可取");
            eq("$prev.position.nope", r.resolve("$prev.position.nope"), "缺字段保留原串");
        });

        T.test("挂账键含 AC 名：不同 AC 同名 step 不串账", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("goto")
                    .thenAccepted("t1", Map.of())
                    .thenAccepted("t2", Map.of());
            PortToolAdapter adapter = PortToolAdapter.builder(port).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunner r = runner(reg).build();

            AcxRunRecord a = r.run(defN("acA", step("s1", "goto", Map.of())), Map.of());
            eq(AcxStatus.PAUSED, a.status(), "acA 受理即暂停");
            AcxRunRecord b = r.run(defN("acB", step("s1", "goto", Map.of())), Map.of());
            eq(AcxStatus.PAUSED, b.status(), "acB 受理即暂停");
            eq(2, port.invokeCount(), "★ 不同 AC 的受理账互不占用（键含 AC 名）");
            eq(2, adapter.pendingCount(), "两笔账都在");

            AcxRunRecord a2 = r.run(defN("acA", step("s1", "goto", Map.of())), Map.of());
            eq(AcxStatus.PAUSED, a2.status(), "acA 再跑命中的是旧账");
            eq(2, port.invokeCount(), "★ 命中挂账不重发");
        });

        T.test("同步 COMPLETED → SUCCESS", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("craft")
                    .thenCompleted(Fake.params("crafted", 5));
            Fake.Registry reg = new Fake.Registry()
                    .add(PortToolAdapter.builder(port).build());
            AcxRunRecord rec = runner(reg).build().run(
                    def(step("s", "craft", Map.of())), Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "当场完成");
            eq(1, port.invokeCount(), "只调一次");
        });

        T.test("受理 → PAUSED（_pause_reason=async_accepted），首发不查探针", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("mine")
                    .thenAccepted("t42", Fake.params("requested", 3));
            FakePorts.Probe probe = new FakePorts.Probe()
                    .thenDone(Fake.params("gathered", 3));
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunRecord rec = runner(reg).build().run(
                    def(step("m", "mine", Map.of())), Map.of());
            eq(AcxStatus.PAUSED, rec.status(), "★ 受理不许当成功");
            eq("async_accepted", rec.pausedReason(), "暂停原因");
            eq(1, port.invokeCount(), "只发起一次");
            eq(0, probe.asked.size(), "首发不问探针");
            eq(1, adapter.pendingCount(), "挂了账");
        });

        // ═══════════════════════════════════════════════════════════════════
        // TODO(AC-B20)：69 号文档第 1 组红线最后一条「脚本在等 ≠ 身体动作已停」
        //
        // 实机读数（live config/numen/monitor/acx.jsonl，2539 行，2026-10-03 核）：
        //     STEP_FAILED|PAUSED = 53   ← 「动作正在世界里执行」被记成了失败
        //     STEP_FAILED|FAIL   = 29   ← 真正失败的
        //     STEP_PAUSED|PAUSED =  1   ← 真正「暂停等结果」的
        //   样本：{"kind":"STEP_FAILED","step_id":"move","status":"PAUSED",
        //          "reason":"goto 已受理，后台执行中: taskId=t1"}
        // ⇒ 「她正在走过去」在日志里长成「这一步失败了」。按 kind 统计失败，
        //   假失败（53）比真失败（29）还多 1.8 倍。
        //
        // 机理（AcxRunner.java:567-572）：execLinear 对任何 !out.isSuccess() 的
        // 结局一律 emit STEP_FAILED，PAUSED 也在内；detail 只有 reason + elapsed_ms，
        // PortToolAdapter.acceptedOutput 里的 task_id / _accepted / _completed
        // 全被丢掉 ⇒ 实机 53 条里带 task_id 的是 0 条，taskId 只以文本混在 reason 里，
        // 机器读不出来。
        // ⇒ 观测面没法把「脚本在等」与「身体已停」分开 —— 正是这条红线要防的那件事。
        //
        // ⚠️ 为什么上面几条测试抓不到：它们只断言 AcxRunRecord.status()（确实是 PAUSED，
        //   那一层是对的），没有一个去看事件流 —— 记录对了、日志错了，比记录错更难发现。
        //
        // 修法（AC-B20，2026-10-06 已修）：execLinear 里 PAUSED 单发 STEP_PAUSED，
        // 并把适配层 task_id/_accepted/_completed/_standing 原样带进 detail（carryAcceptedFields）。
        // ═══════════════════════════════════════════════════════════════════
        T.test("「动作已受理、还在世界里跑」不许被记成 STEP_FAILED（AC-B20，2026-10-06 已修）", () -> {
            Fake.resetCalls();
            FakePorts.Scripted port = FakePorts.Scripted.of("goto")
                    .thenAccepted("t9", Fake.params("moving", true));
            PortToolAdapter adapter = PortToolAdapter.builder(port).build();
            Fake.Registry reg = new Fake.Registry()
                    .add(adapter)
                    .add(Fake.fixed("after", Fake.params("done", true)));
            Fake.Events evs = new Fake.Events();
            AcxRunner r = runner(reg).events(evs).build();

            AcxRunRecord rec = r.run(def(
                    step("move", "goto", Map.of()),
                    step("after", "after", Map.of())), Map.of());

            // ── 前置事实：这一步确实只是「在等」，身体还在动（这几条现在是对的）──
            eq(AcxStatus.PAUSED, rec.status(), "受理即暂停（不是成功）");
            eq("async_accepted", rec.pausedReason(), "暂停原因=已受理仍在跑");
            eq(0, Fake.calls.count("after"), "后续步还没轮到");

            // ── ★ 红线本体 ──
            // 只用一个断言，让失败信息里同时带着两个数字（诊断信息比断言数量值钱）
            int failedLike = 0;
            String taskIdField = "";
            for (AcxEvent e : evs.all) {
                if (e.kind() == AcxEvent.Kind.STEP_FAILED) {
                    failedLike++;
                }
                Object v = e.detail().get("task_id");
                if (v != null && taskIdField.isEmpty()) {
                    taskIdField = String.valueOf(v);
                }
            }
            T.isTrue(failedLike == 0 && "t9".equals(taskIdField),
                    "★ 脚本在等 ≠ 身体动作已停：动作还在飞时不许记成 STEP_FAILED"
                            + "（实际记了 " + failedLike + " 条），"
                            + "且在飞的任务 id 必须是一个可读字段（实际读到 \"" + taskIdField + "\"）");
        });

        T.test("resume 探针 RUNNING → 保持 PAUSED、不重发", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("mine")
                    .thenAccepted("t42", Map.of());
            FakePorts.Probe probe = new FakePorts.Probe().thenRunning("还在挖");
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunner r = runner(reg).build();
            AcxDefinition d = def(step("m", "mine", Map.of()));
            AcxRunRecord rec = r.run(d, Map.of());
            AcxRunRecord rec2 = r.resume(d, rec);
            eq(AcxStatus.PAUSED, rec2.status(), "还在跑");
            eq(1, port.invokeCount(), "★ 不重发命令");
            eq(1, probe.asked.size(), "查了一次");
            eq(1, adapter.pendingCount(), "账保留");
        });

        T.test("resume 探针 DONE → SUCCESS，下游拿到 $move.final_x", () -> {
            Fake.resetCalls();
            FakePorts.Scripted port = FakePorts.Scripted.of("goto")
                    .thenAccepted("t42", Map.of());
            FakePorts.Probe probe = new FakePorts.Probe().thenDone(Fake.params(
                    "final_x", 42.0, "final_y", 64.0, "final_z", 1.0, "ground_y", 64.0));
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
            Fake.Registry reg = new Fake.Registry()
                    .add(adapter)
                    .add(Fake.fixed("echo", Fake.params("seen", true)));
            AcxRunner r = runner(reg).build();
            AcxDefinition d = def(
                    step("move", "goto", Map.of()),
                    step("echo", "echo", Fake.params("v", "$move.final_x")));
            AcxRunRecord rec = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, rec.status(), "先暂停");
            AcxRunRecord rec2 = r.resume(d, rec);
            eq(AcxStatus.SUCCESS, rec2.status(), "完成: " + rec2.errorMessage());
            eq(42.0, Fake.calls.lastParams("echo").get("v"), "★ 下游引用到完成数据");
            eq(0, adapter.pendingCount(), "账销掉");
            eq(1, port.invokeCount(), "全程只发起一次");
        });

        T.test("standing 常驻任务：永远 PAUSED、不查探针、账不销", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("follow")
                    .thenAcceptedStanding("f1", Map.of());
            FakePorts.Probe probe = new FakePorts.Probe()
                    .thenDone(Fake.params("never", true));
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunner r = runner(reg).build();
            AcxDefinition d = def(step("f", "follow", Map.of()));
            AcxRunRecord rec = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, rec.status(), "常驻无终点");
            eq("async_standing", rec.pausedReason(), "原因要能区分");
            AcxRunRecord rec2 = r.resume(d, rec);
            eq(AcxStatus.PAUSED, rec2.status(), "resume 也不许当完成");
            eq(0, probe.asked.size(), "★ 常驻不查探针");
            eq(1, port.invokeCount(), "不重发");
            eq(1, adapter.pendingCount(), "账保留等 task_stop");
        });

        T.test("探针 FAILED → FAIL 并销账", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("mine")
                    .thenAccepted("t42", Map.of());
            FakePorts.Probe probe = new FakePorts.Probe().thenFailed("挖不动");
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunner r = runner(reg).build();
            AcxDefinition d = def(step("m", "mine", Map.of()));
            AcxRunRecord rec = r.run(d, Map.of());
            AcxRunRecord rec2 = r.resume(d, rec);
            eq(AcxStatus.FAIL, rec2.status(), "异步失败就是失败");
            T.isTrue(rec2.errorMessage().contains("异步任务失败"),
                    "错误信息: " + rec2.errorMessage());
            eq(0, adapter.pendingCount(), "账销掉");
            eq(1, port.invokeCount(), "不重发");
        });

        T.test("推模型 markCompleted 后 resume → SUCCESS（不查探针）", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("mine")
                    .thenAccepted("t42", Map.of());
            FakePorts.Probe probe = new FakePorts.Probe().thenRunning("正在跑");
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(probe).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunner r = runner(reg).build();
            AcxDefinition d = def(step("m", "mine", Map.of()));
            AcxRunRecord rec = r.run(d, Map.of());
            adapter.markCompleted("t42", Fake.params("gathered", 3));
            AcxRunRecord rec2 = r.resume(d, rec);
            eq(AcxStatus.SUCCESS, rec2.status(), "★ task_finished 推模型直达完成");
            eq(0, probe.asked.size(), "有推送就不问探针");
            eq(0, adapter.pendingCount(), "账销掉");
        });

        T.test("参数校验在适配层：未知键/缺必填 FAIL 且不发起；ignore_failure 被剥离", () -> {
            AcxPortSchema schema = AcxPortSchema.builder()
                    .param("count", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER)
                            .range(1, 256))
                    .allowUnknown(false)
                    .build();
            FakePorts.Scripted port = new FakePorts.Scripted("mine", schema)
                    .thenCompleted(Fake.params("gathered", 3));
            PortToolAdapter adapter = PortToolAdapter.builder(port).build();
            Fake.Registry reg = new Fake.Registry().add(adapter);
            AcxRunner r = runner(reg).build();

            AcxRunRecord bad = r.run(def(step("m", "mine",
                    Fake.params("relative", true))), Map.of());
            eq(AcxStatus.FAIL, bad.status(), "坏参数直接失败");
            T.isTrue(bad.errorMessage().contains("参数校验失败"),
                    "错误要说清: " + bad.errorMessage());
            eq(0, port.invokeCount(), "★ 校验不过不发起调用");

            AcxRunRecord ok = r.run(def(step("m", "mine",
                    Fake.params("count", 3, "ignore_failure", true))), Map.of());
            eq(AcxStatus.SUCCESS, ok.status(), "合法参数放行");
            eq(3, port.lastCall().get("count"), "参数透传");
            T.isTrue(!port.lastCall().containsKey("ignore_failure"),
                    "★ 控制键不下发给宿主");
        });

        T.test("同一 step 换参数 → 旧账作废、重新发起", () -> {
            FakePorts.Scripted port = FakePorts.Scripted.of("goto")
                    .thenAccepted("t1", Map.of())
                    .thenAccepted("t2", Map.of());
            PortToolAdapter adapter = PortToolAdapter.builder(port).build();
            AcxTool.AcxCallContext c = FakePorts.ctx("t", "s");

            AcxStepOutcome o1 = adapter.execute(Fake.params("x", 1), c);
            eq(AcxStatus.PAUSED, o1.status(), "先受理");
            eq(1, adapter.pendingCount(), "一笔账");

            AcxStepOutcome o2 = adapter.execute(Fake.params("x", 2), c);
            eq(AcxStatus.PAUSED, o2.status(), "换目标重新受理");
            eq(2, port.invokeCount(), "★ 旧账与新目标无关，重发");
            eq(1, adapter.pendingCount(), "账还是一条");

            AcxStepOutcome o3 = adapter.execute(Fake.params("x", 2), c);
            eq(AcxStatus.PAUSED, o3.status(), "同目标命中旧账");
            eq(2, port.invokeCount(), "不重发");
        });

        T.test("桥接注册规则：别名挂载 / acx_ 前缀跳过 / 重名与已有跳过", () -> {
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("mine", Map.of()));
            List<AcxToolPort> ports = new ArrayList<>();
            ports.add(FakePorts.Scripted.of("goto"));
            ports.add(FakePorts.Scripted.of("goto"));
            ports.add(FakePorts.Scripted.of("acx_facade"));
            ports.add(FakePorts.Scripted.of("mine"));
            ports.add(null);
            AcxHostBridge.Report rep = AcxHostBridge.registerAll(reg, ports, null);
            eq(1, rep.registered().size(), "只注册 goto: " + rep.registered());
            eq("goto", rep.registered().get(0), "真名");
            eq(3, rep.aliasNames().size(), "别名: " + rep.aliasNames());
            T.isTrue(reg.contains("navigate_to"), "别名可用");
            T.isTrue(reg.contains("move_to"), "别名可用");
            T.isTrue(!reg.contains("acx_facade"), "★ acx_ 前缀留给门面");
            eq("goto", AcxAliases.canonical("navigate_to"), "别名指向真名");
            eq(4, rep.skipped().size(), "跳过项: " + rep.skipped());
        });

        T.test("端口 schema → 引擎 schema：类型/必填/输出字段并入", () -> {
            AcxPortSchema schema = AcxPortSchema.builder()
                    .param("x", AcxPortSchema.Param.req(AcxPortSchema.Type.NUMBER).nullable())
                    .param("mode", AcxPortSchema.Param.opt(AcxPortSchema.Type.STRING)
                            .withEnum("a", "b"))
                    .param("k", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING))
                    .output("final_x", "task_id")
                    .allowUnknown(false)
                    .build();
            PortToolAdapter a = PortToolAdapter.builder(
                    new FakePorts.Scripted("t", schema)).build();
            AcxToolSchema ts = a.schema();
            eq(AcxToolSchema.ParamType.NUMBER, ts.params().get("x"), "NUMBER 映射");
            T.isTrue(ts.required().contains("x"), "required 带上");
            eq(AcxToolSchema.ParamType.ANY, ts.params().get("final_x"), "★ 输出字段并入");
            T.isTrue(ts.params().containsKey("task_id"), "task_id 也在");

            List<String> errs = schema.validate(Map.of("x", 1, "mode", "c"));
            T.isTrue(errs.stream().anyMatch(e -> e.contains("mode")),
                    "枚举值被抓: " + errs);
            List<String> errs2 = schema.validate(Map.of("x", 1, "bogus", 2));
            T.isTrue(errs2.stream().anyMatch(e -> e.contains("bogus")),
                    "未知键被抓: " + errs2);
            List<String> errs3 = schema.validate(Map.of());
            T.isTrue(errs3.stream().anyMatch(e -> e.contains("k")),
                    "缺必填被抓（nullable 的 x 不算缺）: " + errs3);
        });

        T.test("目录 NumenToolCatalog：名字/字段与实测一致", () -> {
            T.isTrue(NumenToolCatalog.core().size() >= 11,
                    "至少 11 个: " + NumenToolCatalog.core().size());
            NumenToolCatalog.ToolSpec g = NumenToolCatalog.find("get_self_status");
            notNull(g, "有 get_self_status");
            T.isTrue(g.schema().outputFields().contains("position.x"),
                    "★ 嵌套点路径字段在: " + g.schema().outputFields());
            NumenToolCatalog.ToolSpec gt = NumenToolCatalog.find("goto");
            notNull(gt, "有 goto");
            eq(AcxPortSchema.Type.NUMBER, gt.schema().params().get("x").type(), "x 类型");
            T.isTrue(gt.schema().required().contains("x"), "x 必填");
            T.isTrue(gt.schema().outputFields().contains("final_x"), "完成字段");
            T.isTrue(!gt.schema().allowUnknown(), "对齐 additionalProperties=false");
            T.isTrue(NumenToolCatalog.find("不存在") == null, "查不到返回 null");
        });

        T.test("端到端冒烟：读位置→goto→读位置（受理→探针→完成）", () -> {
            Path lib = acLib();
            notNull(lib, "ac-lib 存在");
            FakePorts.Scripted before = FakePorts.Scripted.of("get_self_status")
                    .fallbackCompleted(Fake.params(
                            "position", Fake.params("x", 1.5, "y", 64, "z", 2.5),
                            "hp", 20, "max_hp", 20));
            FakePorts.Scripted move = FakePorts.Scripted.of("goto")
                    .thenAccepted("task-1", Map.of());
            FakePorts.Probe probe = new FakePorts.Probe().thenDone(Fake.params(
                    "final_x", 1.5, "final_y", 64.0, "final_z", 2.5, "ground_y", 64.0));
            Fake.Registry reg = numenRegistryExcept("get_self_status", "goto");
            AcxHostBridge.Report rep = AcxHostBridge.registerAll(
                    reg, List.of(before, move), probe);
            T.isTrue(rep.registered().contains("get_self_status"), "读状态已桥");
            T.isTrue(rep.registered().contains("goto"), "goto 已桥");

            AcxLoader.LoadReport lr = AcxLoader.loadAll(lib, reg, false, true);
            eq(0, lr.errors().size(), "加载错误: " + lr.errors());
            eq(0, lr.warnings().size(), "加载 warning: " + lr.warnings());
            T.isTrue(lr.registered().containsKey("numen_smoke_read_move_read"),
                    "冒烟脚本在: " + lr.registered().keySet());

            AcxRunner r = AcxRunner.builder()
                    .tools(reg)
                    .catalog(lr.catalog())
                    .limits(new AcxRuntimeLimits(200, 20_000L, 8))
                    .build();
            AcxDefinition d = lr.registered().get("numen_smoke_read_move_read");
            AcxRunRecord rec = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, rec.status(), "受理即暂停: " + rec.errorMessage());
            eq("async_accepted", rec.pausedReason(), "暂停原因");
            eq(1, before.invokeCount(), "读了一次");
            eq(1, move.invokeCount(), "发起一次");
            eq(1.5, move.lastCall().get("x"), "X 来自读到的位置");
            eq(64, move.lastCall().get("y"), "Y 来自读到的位置");
            eq(2.5, move.lastCall().get("z"), "Z 来自读到的位置");
            T.isTrue(!move.lastCall().containsKey("ignore_failure"),
                    "控制键不下发");

            AcxRunRecord rec2 = r.resume(d, rec);
            eq(AcxStatus.SUCCESS, rec2.status(), "完成: " + rec2.errorMessage());
            eq(2, before.invokeCount(), "★ resume 后重读位置");
            eq(1, move.invokeCount(), "★ 有账不重发");
            eq(1, probe.asked.size(), "探针问过一次");
        });
    }


    // ═══════════════════════════════════════════════════════════════════
    // 19. 前置条件与参数映射（AC-B3）
    // ═══════════════════════════════════════════════════════════════════

    private static void preconditionsAndBindings() {
        T.group("19 前置条件与参数映射");

        // ── AC 级 precondition ─────────────────────────────────────────

        T.test("precondition 不满足 → PAUSED、断点 0、一步都不跑（不是 FAIL）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 1)));
            AcxDefinition d = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s1", "echo", Map.of())), "", "",
                    List.of(new AcxPrecondition(
                            new AcxCondition("$input.ready", Operator.EQ, true), "没准备好", "先收材料")));
            Fake.Events ev = new Fake.Events();
            AcxRunRecord r = runner(reg).events(ev).build().run(d, Map.of("ready", false));
            eq(AcxStatus.PAUSED, r.status(), "环境没就绪应暂停而不是失败: " + r.errorMessage());
            eq(0, r.completedStepIndex(), "断点留在第 0 步");
            T.isTrue(r.pausedReason() != null && r.pausedReason().startsWith("precondition_failed"),
                    "暂停原因: " + r.pausedReason());
            eq(0, Fake.calls.count("echo"), "前置条件没过，一步都不许跑");
            T.isTrue(ev.has(AcxEvent.Kind.PRECONDITION_FAILED), "要有 PRECONDITION_FAILED 事件");
        });

        T.test("precondition 满足直接通过", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 1)));
            AcxDefinition d = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s1", "echo", Map.of())), "", "",
                    List.of(AcxPrecondition.of(new AcxCondition("$input.ready", Operator.EQ, true))));
            AcxRunRecord r = runner(reg).build().run(d, Map.of("ready", true));
            eq(AcxStatus.SUCCESS, r.status(), r.errorMessage());
            eq(1, Fake.calls.count("echo"), "条件满足应执行");
        });

        T.test("precondition 拦下后 resume 换新 input → 原地重评并跑完", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 1)));
            AcxDefinition d = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s1", "echo", Map.of())), "", "",
                    List.of(AcxPrecondition.of(new AcxCondition("$input.ready", Operator.EQ, true))));
            AcxRunner rr = runner(reg).build();
            AcxRunRecord r1 = rr.run(d, Map.of("ready", false));
            eq(AcxStatus.PAUSED, r1.status(), "第一次应被拦");
            AcxRunRecord r2 = rr.resume(d, r1, Map.of("ready", true));
            eq(AcxStatus.SUCCESS, r2.status(), "带新环境事实续跑: " + r2.errorMessage());
            eq(1, Fake.calls.count("echo"), "重评通过后只跑一次");
        });

        // ── 步骤级 guard ───────────────────────────────────────────────

        T.test("guard 通过 → _guard_passed 可被后续步骤 $prev 引用", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 0)));
            AcxDefinition d = def(
                    step("g", "guard", Fake.params("conditions",
                            List.of(Fake.cond("$input.n", ">", 0)))),
                    step("s1", "echo", Fake.params("flag", "$prev._guard_passed")));
            AcxRunRecord r = runner(reg).build().run(d, Map.of("n", 5));
            eq(AcxStatus.SUCCESS, r.status(), r.errorMessage());
            eq(Boolean.TRUE, Fake.calls.lastParams("echo").get("flag"), "guard 输出要能被读到");
        });

        T.test("guard 不满足默认 → PAUSED，后续步不执行；resume 换 input 重跑 guard", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 0)));
            AcxDefinition d = def(
                    step("g", "guard", Fake.params("conditions",
                            List.of(Fake.cond("$input.n", ">", 0)), "hint", "先弄到 n")),
                    step("s1", "echo", Map.of()));
            AcxRunner rr = runner(reg).build();
            AcxRunRecord r1 = rr.run(d, Map.of("n", -1));
            eq(AcxStatus.PAUSED, r1.status(), "guard 没过应暂停");
            eq(0, Fake.calls.count("echo"), "后续步不许跑");
            T.isTrue(r1.pausedReason() != null && r1.pausedReason().contains("guard[g]"),
                    "暂停原因: " + r1.pausedReason());
            AcxRunRecord r2 = rr.resume(d, r1, Map.of("n", 3));
            eq(AcxStatus.SUCCESS, r2.status(), "重评放行: " + r2.errorMessage());
            eq(1, Fake.calls.count("echo"), "放行后执行一次");
            eq(2, r2.completedStepIndex(), "断点推进到末尾");
        });

        T.test("guard on_fail=fail → FAIL 且带提示（不占 PAUSED 通道）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 0)));
            AcxDefinition d = def(
                    step("g", "guard", Fake.params("conditions",
                            List.of(Fake.cond("$input.ok", "==", true)),
                            "on_fail", "fail", "hint", "先把 ok 打开")),
                    step("s1", "echo", Map.of()));
            AcxRunRecord r = runner(reg).build().run(d, Map.of("ok", false));
            eq(AcxStatus.FAIL, r.status(), "on_fail=fail 应判失败");
            T.isTrue(r.errorMessage() != null && r.errorMessage().contains("先把 ok 打开"),
                    "失败信息要带 hint: " + r.errorMessage());
            eq(0, Fake.calls.count("echo"), "失败后不继续");
        });

        T.test("guard 写法错（缺条件 / 带 children / 未知算子）→ 加载器拒绝", () -> {
            throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"g1\",\"steps\":[{\"id\":\"g\",\"block\":\"guard\",\"params\":{}}]}", null));
            throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"g2\",\"steps\":[{\"id\":\"g\",\"block\":\"guard\",\"params\":{\"conditions\":[{\"field\":\"$input.a\"}]},\"children\":[{\"id\":\"c\",\"block\":\"echo\"}]}]}",
                    null));
            throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"g3\",\"steps\":[{\"id\":\"g\",\"block\":\"guard\",\"params\":{\"conditions\":[{\"field\":\"$input.a\",\"op\":\"~~\",\"value\":1}]}}]}",
                    null));
        });

        // ── precondition 写法错 ────────────────────────────────────────

        T.test("precondition 写法错（缺 field / 未知算子 / 不是数组）→ 加载器拒绝", () -> {
            throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"p1\",\"preconditions\":[{\"op\":\"==\",\"value\":1}],\"steps\":[{\"id\":\"s\",\"block\":\"echo\"}]}",
                    null));
            throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"p2\",\"preconditions\":[{\"field\":\"$input.a\",\"op\":\"~~\",\"value\":1}],\"steps\":[{\"id\":\"s\",\"block\":\"echo\"}]}",
                    null));
            throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"p3\",\"preconditions\":{},\"steps\":[{\"id\":\"s\",\"block\":\"echo\"}]}",
                    null));
        });

        // ── $from / $as / $default ─────────────────────────────────────

        T.test("$from + $as=int 四舍五入；$default 兜底解析不到的引用", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 0)));
            AcxDefinition d = def(step("s1", "echo", Fake.params(
                    "a", Map.of("$from", "$input.n", "$as", "int"),
                    "b", Map.of("$from", "$missing.x", "$default", 7))));
            AcxRunRecord r = runner(reg).build().run(d, Map.of("n", 3.7));
            eq(AcxStatus.SUCCESS, r.status(), r.errorMessage());
            Map<String, Object> p = Fake.calls.lastParams("echo");
            eq(4, ((Number) p.get("a")).intValue(), "$as=int 应四舍五入: " + p.get("a"));
            eq(7, ((Number) p.get("b")).intValue(), "$default 应兜底: " + p.get("b"));
            T.isTrue(p.get("a") instanceof Integer, "转换结果应是整数: " + p.get("a").getClass());
        });

        T.test("引用缺失且无 default → 保留原串 + REF_UNRESOLVED 事件带可用字段提示", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("echo", Fake.params("v", 0)));
            Fake.Events ev = new Fake.Events();
            reg.add(Fake.fixed("src", Fake.params("have1", 1, "have2", 2)));
            AcxDefinition d = def(
                    step("a", "src", Map.of()),
                    step("s1", "echo", Fake.params("x", "$prev.nope")));
            AcxRunRecord r = runner(reg).events(ev).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, r.status(), r.errorMessage());
            eq("$prev.nope", Fake.calls.lastParams("echo").get("x"), "保持原串交给积木校验");
            T.isTrue(ev.has(AcxEvent.Kind.REF_UNRESOLVED), "要有 REF_UNRESOLVED 事件: " + ev.kinds());
            String hint = null;
            for (AcxEvent e : ev.all) {
                if (e.kind() == AcxEvent.Kind.REF_UNRESOLVED) {
                    hint = String.valueOf(e.detail().get("hint"));
                    break;
                }
            }
            T.isTrue(hint != null && hint.contains("have1") && hint.contains("have2"),
                    "提示要列出可用字段: " + hint);
        });

        // ── $filter / $pick / $origin ──────────────────────────────────

        T.test("$filter 按元素字段筛列表；$pick=nearest 按 $origin 选最近", () -> {
            List<Object> items = List.of(
                    Fake.params("id", "far", "kind", "ore", "x", 100, "y", 60, "z", 100),
                    Fake.params("id", "near", "kind", "ore", "x", 2, "y", 60, "z", 2),
                    Fake.params("id", "dirt", "kind", "dirt", "x", 1, "y", 60, "z", 1));
            List<AcxParamBinder.Warning> w = new ArrayList<>();
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params("t", Map.of(
                            "$from", "$scan.items",
                            "$filter", Fake.cond("kind", "==", "ore"),
                            "$pick", "nearest",
                            "$origin", "$input.pos",
                            "$pick_fields", "x,y,z")),
                    Map.of(), Map.of("pos", Fake.params("x", 0, "y", 60, "z", 0)),
                    Map.of("scan", Fake.params("items", items)), w);
            @SuppressWarnings("unchecked")
            Map<String, Object> picked = (Map<String, Object>) out.get("t");
            eq("near", picked.get("id"), "应筛出 ore 再选离原点最近的: " + picked);
            eq(0, w.size(), "不该有提示: " + w);
        });

        T.test("$pick=first / last", () -> {
            List<Object> l = List.of("a", "b", "c");
            Map<String, Object> ctx = Map.of("l", l);
            Map<String, Object> first = AcxParamBinder.bind(
                    Fake.params("v", Map.of("$from", "$input.l", "$pick", "first")),
                    Map.of(), ctx, Map.of(), new ArrayList<>());
            Map<String, Object> last = AcxParamBinder.bind(
                    Fake.params("v", Map.of("$from", "$input.l", "$pick", "last")),
                    Map.of(), ctx, Map.of(), new ArrayList<>());
            eq("a", first.get("v"), "first");
            eq("c", last.get("v"), "last");
        });

        T.test("$as=string_array 标量升数组；描述符嵌在 List 里也生效", () -> {
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params(
                            "a", Map.of("$from", "$input.s", "$as", "string_array"),
                            "b", List.of(Map.of("$from", "$input.n", "$as", "int"))),
                    Map.of(), Map.of("s", "iron", "n", 3), Map.of(), new ArrayList<>());
            eq(List.of("iron"), out.get("a"), "标量应升成单元素数组: " + out.get("a"));
            @SuppressWarnings("unchecked")
            List<Object> b = (List<Object>) out.get("b");
            eq(1, b.size(), "列表内描述符也应转换: " + b);
            eq(3, ((Number) b.get(0)).intValue(), "嵌入描述符转换值: " + b);
        });

        T.test("$origin 解析不到 → 退回第一个 + 一条提示（不炸）", () -> {
            List<Object> items = List.of(
                    Fake.params("id", "a", "x", 1, "y", 2, "z", 3),
                    Fake.params("id", "b", "x", 9, "y", 9, "z", 9));
            List<AcxParamBinder.Warning> w = new ArrayList<>();
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params("t", Map.of("$from", "$input.items",
                            "$pick", "nearest", "$origin", "$missing.pos")),
                    Map.of(), Map.of("items", items), Map.of(), w);
            @SuppressWarnings("unchecked")
            Map<String, Object> picked = (Map<String, Object>) out.get("t");
            eq("a", picked.get("id"), "退回第一个");
            eq(1, w.size(), "要有一条提示: " + w);
        });

        T.test("★ $origin 是裸 {x,y,z} 时 nearest 必须真按距离选（2026-10-09 引羊实测缺陷）", () -> {
            // 元素带嵌套 position（pick_fields 指向它），origin 是 get_self_status 那种裸 {x,y,z}
            List<Object> items = List.of(
                    Fake.params("id", "far", "position", Fake.params("x", 100, "y", 64, "z", 100)),
                    Fake.params("id", "near", "position", Fake.params("x", 3, "y", 64, "z", 3)));
            List<AcxParamBinder.Warning> w = new ArrayList<>();
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params("t", Map.of("$from", "$input.items",
                            "$pick", "nearest",
                            "$origin", "$input.origin",
                            "$pick_fields", "position.x,position.y,position.z")),
                    Map.of(), Map.of("items", items, "origin", Fake.params("x", 0, "y", 64, "z", 0)),
                    Map.of(), w);
            @SuppressWarnings("unchecked")
            Map<String, Object> picked = (Map<String, Object>) out.get("t");
            eq("near", picked.get("id"),
                    "★ origin 是裸 {x,y,z} 时必须按距离选最近，不能退回列表第一个（旧缺陷会选 far）");
            eq(0, w.size(), "正常解析不该产生 REF_UNRESOLVED 提示: " + w);
        });

        T.test("$pick_fields 描述的 origin（坐标直接就是 x/y/z）也照旧工作", () -> {
            List<Object> items = List.of(
                    Fake.params("id", "far", "x", 100, "y", 64, "z", 100),
                    Fake.params("id", "near", "x", 3, "y", 64, "z", 3));
            List<AcxParamBinder.Warning> w = new ArrayList<>();
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params("t", Map.of("$from", "$input.items",
                            "$pick", "nearest", "$origin", "$input.origin")),
                    Map.of(), Map.of("items", items, "origin", Fake.params("x", 0, "y", 64, "z", 0)),
                    Map.of(), w);
            @SuppressWarnings("unchecked")
            Map<String, Object> picked = (Map<String, Object>) out.get("t");
            eq("near", picked.get("id"), "默认字段写法必须仍然有效");
        });

        // ── 指纹 / 落库 ────────────────────────────────────────────────

        T.test("precondition 参与指纹：加/改条件都会让指纹变化（resume 契约不被绕过）", () -> {
            AcxDefinition a = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "echo", Map.of())), "", "");
            AcxDefinition b = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "echo", Map.of())), "", "",
                    List.of(AcxPrecondition.of(new AcxCondition("$input.ready", Operator.EQ, true))));
            AcxDefinition c2 = new AcxDefinition("t", "1", "", List.of(),
                    List.of(step("s", "echo", Map.of())), "", "",
                    List.of(AcxPrecondition.of(new AcxCondition("$input.ready", Operator.NE, true))));
            T.isTrue(!AcxFingerprint.of(a).equals(AcxFingerprint.of(b)), "加 precondition 应变指纹");
            T.isTrue(!AcxFingerprint.of(b).equals(AcxFingerprint.of(c2)), "改算子应变指纹");
        });

        T.test("hp_guard_goto 进 ac-lib stable（guard 示例用真形状写，全量 strict 自检在 17 组）", () -> {
            AcxLoader.LoadReport rep = loadRealLib();
            T.isTrue(rep.registered().containsKey("hp_guard_goto"),
                    "守卫示例应在 stable: " + rep.errors());
            T.isTrue(!rep.registered().containsKey("guarded_ore_survey"),
                    "★ DD 的 guarded_ore_survey 形状不兼容，已降级 reference/");
        });
    }

    // ── 20. 质量台账与生命周期（AC-B4）────────────────────────────

    private static AcxRunRecord rec(String name, String version, AcxStatus st, long ms) {
        return AcxRunRecord.builder()
                .runId("r" + ms).acName(name).acVersion(version).fingerprint("fp")
                .input(Map.of()).status(st).elapsedMs(ms).timestamp(1000 + ms)
                .pausedReason(st == AcxStatus.PAUSED ? "等材料" : null)
                .failedStep(st == AcxStatus.FAIL ? "s" : null)
                .build();
    }

    /**
     * 记录 / 质量统计 / 生命周期回归。重点钉四件事：
     * ① SUCCESS 也要被台账数到（JSONL 仍只落非成功）；
     * ② 运行标记按「AC@版本」维度，跨版本可聚合；
     * ③ 成功经验回流只在 SUCCESS，且回流失败不改执行结果；
     * ④ 生命周期 GENERATED→PENDING→STABLE→REJECTED，只有 STABLE 能 active、能回滚。
     */
    private static void qualityAndLifecycle() {
        T.group("20 质量台账与生命周期（AC-B4）");

        T.test("SUCCESS 也通知 store；JSONL 仍只落非成功", () -> {
            Path f = tmpDir().resolve("b4-runs.jsonl");
            Fake.resetCalls();
            AcxQualityLedger ledger = new AcxQualityLedger();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("ok", Map.of()))
                    .add(Fake.failing("bad", "炸"));
            AcxRunner r1 = AcxRunner.builder().tools(reg).store(ledger).build();
            r1.run(def(step("s", "ok", Map.of())), Map.of());
            eq(1, ledger.runs("t", "1"), "★ 台账数到 SUCCESS");
            eq(1, ledger.stats("t", "1").success(), "成功计数");
            try (JsonlRecordStore j = new JsonlRecordStore(f)) {
                AcxRunner r2 = AcxRunner.builder().tools(reg).store(j).build();
                r2.run(def(step("s", "ok", Map.of())), Map.of());
                r2.run(def(step("s", "bad", Map.of())), Map.of());
                eq(1, j.recent().size(), "JSONL 只留 FAIL");
            }
            eq(1, countLines(f), "文件里只有一行");
        });

        T.test("台账统计四种终态与质量字段", () -> {
            AcxQualityLedger led = new AcxQualityLedger();
            led.append(rec("t", "1", AcxStatus.SUCCESS, 100));
            led.append(rec("t", "1", AcxStatus.PAUSED, 200));
            led.append(rec("t", "1", AcxStatus.FAIL, 300));
            led.append(rec("t", "1", AcxStatus.TIMEOUT, 400));
            AcxRunStats s = led.stats("t", "1");
            eq(4, s.runs(), "总次数");
            eq(1, s.success(), "成功");
            eq(1, s.paused(), "暂停");
            eq(1, s.failed(), "失败");
            eq(1, s.timedOut(), "超时");
            eq(250L, s.avgElapsedMs(), "平均耗时");
            eq(400L, s.maxElapsedMs(), "最长耗时");
            eq("TIMEOUT", s.lastStatus(), "最后状态");
            T.isTrue(s.summary().contains("超时 1"), "摘要带超时次数: " + s.summary());
        });

        T.test("运行标记：按版本维度，没跑过为 false", () -> {
            AcxQualityLedger led = new AcxQualityLedger();
            T.isTrue(!led.hasRun("t", "1"), "还没跑");
            led.append(rec("t", "1", AcxStatus.SUCCESS, 10));
            T.isTrue(led.hasRun("t", "1"), "跑过了");
            T.isTrue(!led.hasRun("t", "2"), "别的版本没跑");
            T.isTrue(led.lastRunAt("t", "1") > 0, "有最后运行时刻");
            eq(0, led.runs("t", "2"), "没跑过次数 0");
        });

        T.test("跨版本聚合 statsOf", () -> {
            AcxQualityLedger led = new AcxQualityLedger();
            led.append(rec("t", "1", AcxStatus.SUCCESS, 10));
            led.append(rec("t", "2", AcxStatus.FAIL, 30));
            AcxRunStats agg = led.statsOf("t");
            notNull(agg, "有聚合");
            eq(2, agg.runs(), "两版合计");
            eq(1, agg.success(), "成功合计");
            eq(1, agg.failed(), "失败合计");
            eq(20L, agg.avgElapsedMs(), "平均");
            T.isTrue(led.statsOf("nope") == null, "没跑过返回 null");
        });

        T.test("台账 save/load 往返保留运行标记", () -> {
            Path f = tmpDir().resolve("ledger.json");
            AcxQualityLedger led = new AcxQualityLedger();
            led.append(rec("t", "1", AcxStatus.SUCCESS, 10));
            led.append(rec("t", "1", AcxStatus.PAUSED, 20));
            led.save(f);
            AcxQualityLedger led2 = new AcxQualityLedger();
            led2.load(f);
            eq(2, led2.runs("t", "1"), "次数读回");
            eq(1, led2.stats("t", "1").paused(), "暂停数读回");
            T.isTrue(led2.hasRun("t", "1"), "运行标记读回");
        });

        T.test("坏台账文件按空处理，不挡住执行", () -> {
            Path f = tmpDir().resolve("bad-ledger.json");
            write(f, "{不是JSON");
            AcxQualityLedger led = new AcxQualityLedger();
            led.load(f);
            eq(0, led.keys().size(), "空台账");
            led.append(rec("t", "1", AcxStatus.SUCCESS, 10));
            eq(1, led.runs("t", "1"), "照常记账");
        });

        T.test("成功经验回流：SUCCESS 触发、PAUSED 不触发", () -> {
            List<AcxExperience> got = new ArrayList<>();
            AcxRunner r = AcxRunner.builder()
                    .tools(new Fake.Registry().add(Fake.fixed("ok", Map.of())))
                    .experienceSink(got::add)
                    .build();
            r.run(defN("acxE", step("s", "ok", Map.of())), Map.of());
            eq(1, got.size(), "一次成功一条经验");
            eq("acxE", got.get(0).acName(), "名字");
            eq("1", got.get(0).acVersion(), "版本");
            Fake.Registry regP = new Fake.Registry().add(Fake.pauser("p", "等材料"));
            AcxRunner r2 = AcxRunner.builder().tools(regP).experienceSink(got::add).build();
            r2.run(defN("acxP", step("s", "p", Map.of())), Map.of());
            eq(1, got.size(), "PAUSED 不进经验库");
        });

        T.test("经验回流抛异常不改执行结果", () -> {
            AcxRunner r = AcxRunner.builder()
                    .tools(new Fake.Registry().add(Fake.fixed("ok", Map.of())))
                    .experienceSink(e -> { throw new RuntimeException("坏经验库"); })
                    .build();
            eq(AcxStatus.SUCCESS, r.run(def(step("s", "ok", Map.of())), Map.of()).status(),
                    "★ 观测炸了不影响执行");
        });

        T.test("生命周期：GENERATED→PENDING→STABLE，只有 STABLE 能 active", () -> {
            Path f = tmpDir().resolve("b4-lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "初版");
            eq(AcxLifecycleState.GENERATED, lib.status("t", "1"), "刚发布");
            lib.markPending("t", "1", "跑过 3 次");
            eq(AcxLifecycleState.PENDING, lib.status("t", "1"), "有运行标记");
            lib.approve("t", "1", "user", "验过了");
            eq(AcxLifecycleState.STABLE, lib.status("t", "1"), "批准");
            eq("1", lib.activeVersion("t"), "active");
            notNull(lib.active("t"), "可加载");
        });

        T.test("否决：REJECTED 不能 approve/markPending，reopen 后回到 GENERATED", () -> {
            Path f = tmpDir().resolve("b4-lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "初版");
            lib.reject("t", "1", "太重了");
            eq(AcxLifecycleState.REJECTED, lib.status("t", "1"), "被否决");
            throwsA(IllegalStateException.class, () -> lib.approve("t", "1", "user", ""));
            throwsA(IllegalStateException.class, () -> lib.markPending("t", "1", ""));
            lib.reopen("t", "1");
            eq(AcxLifecycleState.GENERATED, lib.status("t", "1"), "reopen");
        });

        T.test("否决 active 版本：active 被撤下，库还在", () -> {
            Path f = tmpDir().resolve("b4-lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "初版");
            lib.approve("t", "1", "user", "");
            lib.reject("t", "1", "游戏里炸");
            T.isTrue(lib.activeVersion("t") == null, "撤下 active");
            T.isTrue(!lib.isApproved("t"), "不再是已批准");
            throwsA(IllegalStateException.class, () -> lib.active("t"));
            eq(1, lib.versions("t").size(), "版本仍在库中可回查");
        });

        T.test("rollback 只能到 STABLE 版本", () -> {
            Path f = tmpDir().resolve("b4-lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "v1");
            lib.publish(new AcxDefinition("t", "2", "", List.of(),
                    List.of(step("s", "blk", Fake.params("x", 1))), "", ""), "v2");
            lib.publish(new AcxDefinition("t", "3", "", List.of(),
                    List.of(step("s", "blk", Map.of())), "", ""), "v3");
            lib.approve("t", "1", "user", "");
            lib.approve("t", "2", "user", "");
            lib.rollback("t", "1", "回退");
            eq("1", lib.activeVersion("t"), "回到 v1");
            lib.approve("t", "2", "user", "再上");
            eq("2", lib.activeVersion("t"), "再切回 v2");
            throwsA(IllegalStateException.class, () -> lib.rollback("t", "3", "v3 没批过"));
        });

        T.test("生命周期落盘往返；旧档（无 status）按 active=STABLE 兼容", () -> {
            Path f = tmpDir().resolve("b4-lib.json");
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("blk", Map.of()));
            FileAcxLibrary lib = new FileAcxLibrary(f, reg);
            lib.publish(def(step("s", "blk", Map.of())), "v1");
            lib.markPending("t", "1", "跑过");
            lib.publish(new AcxDefinition("t", "2", "", List.of(),
                    List.of(step("s", "blk", Map.of())), "", ""), "v2");
            lib.reject("t", "2", "坏");
            FileAcxLibrary lib2 = new FileAcxLibrary(f, reg);
            lib2.load();
            eq(AcxLifecycleState.PENDING, lib2.status("t", "1"), "PENDING 读回");
            eq(AcxLifecycleState.REJECTED, lib2.status("t", "2"), "REJECTED 读回");

            Path f2 = tmpDir().resolve("legacy-lib.json");
            String old;
            try {
                old = Files.readString(f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            old = old.replaceAll("\"status\":\"[A-Z]+\",", "");
            write(f2, old);
            FileAcxLibrary lib3 = new FileAcxLibrary(f2, reg);
            lib3.load();
            eq(AcxLifecycleState.GENERATED, lib3.status("t", "1"), "旧档非 active 视为 GENERATED");
            lib3.approve("t", "1", "user", "旧档补批");
            eq(AcxLifecycleState.STABLE, lib3.status("t", "1"), "补批后 STABLE");
        });

        T.test("AcxLifecycleState 迁移白名单", () -> {
            T.isTrue(AcxLifecycleState.GENERATED.canTransitionTo(AcxLifecycleState.PENDING), "G→P");
            T.isTrue(AcxLifecycleState.PENDING.canTransitionTo(AcxLifecycleState.STABLE), "P→S");
            T.isTrue(AcxLifecycleState.STABLE.canTransitionTo(AcxLifecycleState.REJECTED), "S→R");
            T.isTrue(AcxLifecycleState.REJECTED.canTransitionTo(AcxLifecycleState.GENERATED), "R→G");
            T.isTrue(!AcxLifecycleState.STABLE.canTransitionTo(AcxLifecycleState.PENDING), "S 不能回 P");
            T.isTrue(!AcxLifecycleState.REJECTED.canTransitionTo(AcxLifecycleState.STABLE), "R 不能直接 S");
            T.isTrue(AcxLifecycleState.STABLE.isActive(), "只有 STABLE 是 active");
        });
    }


    // ═══════════════════════════════════════════════════════════════════
    // 21. 宿主门面（会话层 / 游戏线程门 / Facade 协议）
    // ═══════════════════════════════════════════════════════════════════

    private static void hostFacade() {
        T.group("21 宿主门面与会话层");

        T.test("runId 可外部指定（executionId 只有一份）", () -> {
            Fake.resetCalls();
            AcxRunner r = runner(new Fake.Registry().add(Fake.fixed("a", Map.of()))).build();
            AcxRunRecord rec = r.run(def(step("s", "a", Map.of())), Map.of(), "run-x");
            T.eq("run-x", rec.runId(), "外部 runId 透传");
        });

        T.test("resume 复用原 runId（不再换新 id）", () -> {
            Fake.resetCalls();
            Fake.PauseOnce once = new Fake.PauseOnce();
            AcxRunner r = runner(new Fake.Registry().add(Fake.pauserOnce("p", once, "等"))).build();
            AcxDefinition d = def(step("s", "p", Map.of()));
            AcxRunRecord r1 = r.run(d, Map.of());
            T.eq(AcxStatus.PAUSED, r1.status(), "首跑暂停");
            AcxRunRecord r2 = r.resume(d, r1);
            T.eq(AcxStatus.SUCCESS, r2.status(), "续跑成功");
            T.eq(r1.runId(), r2.runId(), "两次同 runId");
        });

        T.test("游戏线程门：包装后 name/schema 代理、执行过门", () -> {
            Fake.resetCalls();
            AtomicBoolean gated = new AtomicBoolean(false);
            AcxGameThreadGate gate = callable -> {
                gated.set(true);
                return callable.call();
            };
            AcxTool inner = Fake.fixed("g", Fake.params("v", 1));
            AcxTool tool = AcxGatedTool.of(inner, gate);
            T.eq("g", tool.name(), "名字代理");
            T.isTrue(tool.schema().params().containsKey("v"), "schema 代理");
            AcxStepOutcome out = tool.execute(Map.of(), FakePorts.ctx("t", "s"));
            T.eq(AcxStatus.SUCCESS, out.status(), "执行成功");
            T.isTrue(gated.get(), "确实走了门");
            T.eq(1, out.output().get("v"), "输出透传");
        });

        T.test("游戏线程门：门抛异常 → FAIL 且消息带积木名", () -> {
            AcxGameThreadGate boom = callable -> {
                throw new IllegalStateException("no main thread");
            };
            AcxTool tool = AcxGatedTool.of(Fake.fixed("g", Map.of()), boom);
            AcxStepOutcome out = tool.execute(Map.of(), FakePorts.ctx("t", "s"));
            T.eq(AcxStatus.FAIL, out.status(), "降级 FAIL");
            T.contains(out.message(), "游戏线程门", "消息含门名");
            T.contains(out.message(), "g", "消息含积木名");
        });

        T.test("游戏线程门：门返回 null → FAIL", () -> {
            AcxGameThreadGate nullGate = callable -> null;
            AcxTool tool = AcxGatedTool.of(Fake.fixed("g", Map.of()), nullGate);
            AcxStepOutcome out = tool.execute(Map.of(), FakePorts.ctx("t", "s"));
            T.eq(AcxStatus.FAIL, out.status(), "降级 FAIL");
            T.contains(out.message(), "返回了 null", "说明原因");
        });

        T.test("游戏线程门为 null → 原样返回不包装", () -> {
            AcxTool inner = Fake.fixed("g", Map.of());
            T.isTrue(AcxGatedTool.of(inner, null) == inner, "同一实例直通");
        });

        T.test("会话层：直接执行器下 start 即完成、未知查询为空", () -> {
            Fake.resetCalls();
            AcxRunner r = runner(new Fake.Registry().add(Fake.fixed("ok", Map.of()))).build();
            AcxSessionManager sm = new AcxSessionManager(r, Runnable::run);
            String id = sm.start(def(step("s", "ok", Map.of())), Map.of());
            T.notNull(id, "拿到 runId");
            AcxSessionManager.Session s = sm.session(id);
            T.notNull(s, "会话在");
            T.notNull(s.record(), "直接执行器下已完成");
            T.eq(AcxStatus.SUCCESS, s.record().status(), "成功");
            T.eq(id, s.record().runId(), "记录里同 id");
            T.isTrue(!sm.isRunning(id), "不在跑");
            T.isTrue(sm.session("nope") == null, "未知会话 null");
            T.isTrue(!sm.isRunning("nope"), "未知不在跑");
        });

        T.test("会话层：resume 只认 PAUSED 且保持 runId", () -> {
            Fake.resetCalls();
            Fake.PauseOnce once = new Fake.PauseOnce();
            AcxRunner r = runner(new Fake.Registry().add(Fake.pauserOnce("p", once, "等"))).build();
            AcxSessionManager sm = new AcxSessionManager(r, Runnable::run);
            String id = sm.start(def(step("s", "p", Map.of())), Map.of());
            T.eq(AcxStatus.PAUSED, sm.session(id).record().status(), "首跑暂停");
            T.isTrue(sm.resume(id, Map.of("env", 1)), "续跑受理");
            AcxRunRecord rec = sm.session(id).record();
            T.eq(AcxStatus.SUCCESS, rec.status(), "续跑完成");
            T.eq(id, rec.runId(), "runId 不变");
            T.isTrue(!sm.resume(id, Map.of()), "已结束不能再续");
        });

        // ★★ TODO(AC-B22) 已知红 —— 69 号文档「第 1 组红线 / 第 2 组红线：任务与版本关联」
        //
        // 缺陷：**会话层续跑时从不重新解析 AC 定义**，于是 AcxRunner.checkResumable
        // 的三道「内容」门在生产路径上**恒为真**，整条校验链只剩一道「断点越界」还有效。
        //
        // 机理（三行，都是生产代码）：
        //   AcxSessionManager.Session.definition 是 final、只在 start() 时赋值（:46/:57/:124）
        //   AcxSessionManager.resume 用 s.definition 校验并续跑（:165 / :169）
        //   AcxRunner.runFrom 把记录的 fingerprint 取成 AcxFingerprint.of(def)（:142），
        //       而 checkResumable 里的 fp 也是 AcxFingerprint.of(def)（:244）
        // ⇒ 传给校验的 def 与当初算出 prior.fingerprint() 的 def 是**同一个对象**，
        //   所以 name / version / fingerprint 三道门**怎么比都相等**。
        //   而 AcxFacade.execute 每次都经 resolve() 重新查库（:79/:294-324），
        //   唯独 resume 只收 run_id + input（:133-136），**没有再查一次当前生效的定义**。
        //
        // 后果比「校验不触发」更重：暂停期间有人改了脚本（改了内容但忘了升版本号），
        // 续跑会**静默按旧定义跑完**——改动对这条会话永远不生效，且没有任何一处提示。
        // AcxRunner 的类注释（:199-202）写着「定义变了还静默从旧断点续跑会产出无法解释的结果」，
        // 这条链本来就是为拦住它写的；AC-B21（prior.fingerprint()==null 整道门跳过）
        // 与本条是同一扇门的两个洞，本条是门后那一整条路都没走到。
        //
        // 为什么现有的 210 项抓不到：resumeContract 组测的是**执行器层** checkResumable
        // 被传入一个**不同的**定义（:679），那道门在执行器层是有效的；
        // 缺的是**会话/门面层**——没人验证过「续跑时用的定义是不是当前生效的那一份」。
        // （AC-B22，2026-10-06 已修：AcxFacade.resume 按名字重查当前定义再走校验链；
        //   AcxSessionManager.resume 新增带 currentDefinition 的重载。）
        T.test("会话层：暂停期间脚本被改过 → 续跑必须被拒，不得静默按旧定义跑完"
                + "（AC-B22，2026-10-06 已修）", () -> {
            Fake.resetCalls();
            Fake.PauseOnce once = new Fake.PauseOnce();
            Fake.Registry blocks = new Fake.Registry()
                    .add(Fake.pauserOnce("p", once, "等"))
                    .add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxSessionManager sm = new AcxSessionManager(r, Runnable::run);
            // 库里当前生效的定义（AcxFacade.execute 每次都会重新查它）
            AtomicReference<AcxDefinition> live = new AtomicReference<>(
                    defN("t", step("s", "p", Map.of())));
            AcxFacade facade = new AcxFacade(sm, n -> live.get(), blocks, null);

            Map<String, Object> started = facade.execute(Map.of("ac_name", "t"));
            T.isTrue(Boolean.TRUE.equals(started.get("success")), "首跑受理");
            String runId = String.valueOf(castData(started).get("run_id"));
            T.eq("PAUSED", castData(facade.status(Map.of("run_id", runId))).get("state"), "首跑暂停");
            String fpBefore = AcxFingerprint.of(live.get());
            String idBefore = sm.session(runId).record().fingerprint();

            // ★ 暂停期间有人改了脚本：内容变了（多了一步），版本号忘了升 —— 最常见的手滑。
            //   注意门面的 resolve() 现在会返回这份新定义；resume 却没有再查一次。
            live.set(defN("t", step("s", "p", Map.of()), step("s2", "ok", Map.of())));
            T.isTrue(!AcxFingerprint.of(live.get()).equals(fpBefore), "★ 前置事实：库里那份指纹确实变了");

            Map<String, Object> resp = facade.resume(Map.of("run_id", runId));

            // 三条断言合成一个失败信息：读的人能同时看到「它改了什么」与「现在跑的是什么」。
            T.isTrue(!Boolean.TRUE.equals(resp.get("success")),
                    "★ 脚本在暂停期间变了，续跑必须被明确拒绝（不能静默按旧定义跑）"
                            + " —— 当前库里的指纹=" + AcxFingerprint.of(live.get())
                            + " / 记录里的指纹=" + idBefore
                            + " / 实际回包=" + resp.get("message"));
            T.contains(String.valueOf(resp.get("message")), "变",
                    "★ 拒绝文案必须点明是「变了」这一项 ——"
                            + "「脚本被改过」与「会话不存在」处置不同：前者要人重跑，后者直接放弃");
            // 拒绝之后状态不许被推进：仍然 PAUSED、仍然停在同一步。
            T.eq("PAUSED", castData(facade.status(Map.of("run_id", runId))).get("state"),
                    "被拒后不许偷偷跑起来");
        });

        T.test("会话层：协作式取消（真线程 + 等待积木）", () -> {
            Fake.resetCalls();
            AtomicBoolean started = new AtomicBoolean(false);
            AcxTool waiter = new Fake.Base("waiter", Map.of()) {
                @Override
                public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
                    started.set(true);
                    long deadline = System.currentTimeMillis() + 3000;
                    while (!ctx.isCancelRequested() && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    return ctx.isCancelRequested()
                            ? AcxStepOutcome.paused("waiter saw cancel",
                                    Map.of("_pause_reason", "cancel_seen_by_tool"))
                            : AcxStepOutcome.failed("waiter timed out");
                }
            };
            AcxRunner r = runner(new Fake.Registry().add(waiter)).build();
            ExecutorService ex = Executors.newSingleThreadExecutor();
            try {
                AcxSessionManager sm = new AcxSessionManager(r, ex);
                String id = sm.start(def(step("w", "waiter", Map.of())), Map.of());
                long waitStart = System.currentTimeMillis() + 3000;
                while (!started.get() && System.currentTimeMillis() < waitStart) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                T.isTrue(started.get(), "等待积木开跑");
                T.isTrue(sm.cancel(id), "取消受理");
                long recDeadline = System.currentTimeMillis() + 3000;
                while (sm.session(id).record() == null && System.currentTimeMillis() < recDeadline) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                AcxRunRecord rec = sm.session(id).record();
                T.notNull(rec, "取消后落记录");
                if (rec != null) {
                    T.eq(AcxStatus.PAUSED, rec.status(), "取消 → PAUSED");
                    T.eq(id, rec.runId(), "runId 不变");
                }
                T.isTrue(!sm.cancel("nope"), "未知会话取消 false");
            } finally {
                ex.shutdownNow();
            }
        });

        T.test("会话层：maxFinished 淘汰 + purgeFinished", () -> {
            Fake.resetCalls();
            AcxRunner r = runner(new Fake.Registry().add(Fake.fixed("ok", Map.of()))).build();
            AcxSessionManager sm = new AcxSessionManager(r, Runnable::run, 2);
            sm.start(def(step("s", "ok", Map.of())), Map.of());
            sm.start(def(step("s", "ok", Map.of())), Map.of());
            sm.start(def(step("s", "ok", Map.of())), Map.of());
            T.eq(2, sm.size(), "只留 2 条已完成会话");
            T.eq(0, sm.runningCount(), "没有在跑的");
            T.eq(2, sm.finishedCount(), "2 条已完成");
            int purged = sm.purgeFinished();
            T.eq(2, purged, "清空已完成");
            T.eq(0, sm.size(), "清后为空");
        });

        T.test("门面：ac_json 执行 + status 终态", () -> {
            Fake.resetCalls();
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxSessionManager sm = new AcxSessionManager(r, Runnable::run);
            AcxFacade facade = new AcxFacade(sm, name -> null, blocks, null);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("ac_json", "{\"name\":\"inline\",\"version\":\"1\",\"steps\":[{\"id\":\"s1\",\"block\":\"ok\"}]}");
            Map<String, Object> resp = facade.execute(req);
            T.isTrue(Boolean.TRUE.equals(resp.get("success")), "受理成功");
            Map<String, Object> data = castData(resp);
            T.eq("RUNNING", data.get("status"), "立刻回 RUNNING");
            T.eq("inline", data.get("ac_name"), "名字回包");
            String runId = String.valueOf(data.get("run_id"));
            Map<String, Object> st = facade.status(Map.of("run_id", runId));
            T.isTrue(Boolean.TRUE.equals(st.get("success")), "查询成功");
            T.eq("SUCCESS", castData(st).get("state"), "已直接执行完");
        });

        T.test("门面：必须且只能给 ac_json/ac_name 之一", () -> {
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxFacade facade = new AcxFacade(new AcxSessionManager(r, Runnable::run), name -> null, blocks, null);
            Map<String, Object> resp = facade.execute(Map.of());
            T.isTrue(Boolean.FALSE.equals(resp.get("success")), "空参数拒绝");
            T.contains(String.valueOf(resp.get("message")), "只能", "说明二选一");
        });

        T.test("门面：按名字执行 + 未找到 + 坏 JSON", () -> {
            Fake.resetCalls();
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxDefinition demo = defN("demo", step("s", "ok", Map.of()));
            AcxFacade facade = new AcxFacade(new AcxSessionManager(r, Runnable::run),
                    name -> "demo".equals(name) ? demo : null, blocks, null);
            Map<String, Object> okResp = facade.execute(Map.of("ac_name", "demo"));
            T.isTrue(Boolean.TRUE.equals(okResp.get("success")), "按名找到");
            T.eq("demo", castData(okResp).get("ac_name"), "名字回包");
            Map<String, Object> miss = facade.execute(Map.of("ac_name", "nope"));
            T.isTrue(Boolean.FALSE.equals(miss.get("success")), "未知名字拒绝");
            T.contains(String.valueOf(miss.get("message")), "未找到", "说明未找到");
            Map<String, Object> bad = facade.execute(Map.of("ac_json", "{oops"));
            T.isTrue(Boolean.FALSE.equals(bad.get("success")), "坏 JSON 拒绝");
            T.contains(String.valueOf(bad.get("message")), "解析", "说明解析失败");
        });

        T.test("门面：resume 只认 PAUSED、未知 run_id 拒绝", () -> {
            Fake.resetCalls();
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxDefinition demo = defN("demo", step("s", "ok", Map.of()));
            AcxFacade facade = new AcxFacade(new AcxSessionManager(r, Runnable::run), name -> demo, blocks, null);
            Map<String, Object> ex = facade.execute(Map.of("ac_name", "demo"));
            String runId = String.valueOf(castData(ex).get("run_id"));
            Map<String, Object> res = facade.resume(Map.of("run_id", runId));
            T.isTrue(Boolean.FALSE.equals(res.get("success")), "已完成不能续");
            T.contains(String.valueOf(res.get("message")), "PAUSED", "说明只认 PAUSED");
            Map<String, Object> unk = facade.resume(Map.of("run_id", "nope"));
            T.isTrue(Boolean.FALSE.equals(unk.get("success")), "未知拒绝");
            T.contains(String.valueOf(unk.get("message")), "未知", "说明未知");
        });

        T.test("门面：cancel 未知/已结束拒绝", () -> {
            Fake.resetCalls();
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxFacade facade = new AcxFacade(new AcxSessionManager(r, Runnable::run), name -> null, blocks, null);
            Map<String, Object> unk = facade.cancel(Map.of("run_id", "nope"));
            T.isTrue(Boolean.FALSE.equals(unk.get("success")), "未知拒绝");
            T.contains(String.valueOf(unk.get("message")), "未知", "说明未知");
        });

        T.test("门面：publish → approve → 回滚（人工批准不可绕过）", () -> {
            Fake.resetCalls();
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxSessionManager sm = new AcxSessionManager(r, Runnable::run);
            FileAcxLibrary lib = new FileAcxLibrary(tmpDir().resolve("facade-lib.json"), blocks);
            AcxFacade facade = new AcxFacade(sm, name -> null, blocks, lib);
            String v1 = "{\"name\":\"t\",\"version\":\"1\",\"steps\":[{\"id\":\"s1\",\"block\":\"ok\"}]}";
            String v2 = "{\"name\":\"t\",\"version\":\"2\",\"steps\":[{\"id\":\"s1\",\"block\":\"ok\"}]}";
            Map<String, Object> pub1 = facade.publish(Map.of("ac_json", v1));
            T.isTrue(Boolean.TRUE.equals(pub1.get("success")), "发布成功");
            T.eq("GENERATED", castData(pub1).get("status"), "发布后 GENERATED");
            T.eq(Boolean.FALSE, castData(pub1).get("active"), "不自动切 active");
            Map<String, Object> ap1 = facade.approve(Map.of("name", "t", "version", "1"));
            T.isTrue(Boolean.TRUE.equals(ap1.get("success")), "批准成功");
            T.eq("1", castData(ap1).get("active_version"), "v1 生效");
            Map<String, Object> pub2 = facade.publish(Map.of("ac_json", v2));
            T.isTrue(Boolean.TRUE.equals(pub2.get("success")), "v2 发布");
            facade.approve(Map.of("name", "t", "version", "2"));
            Map<String, Object> rb = facade.rollback(Map.of("name", "t", "version", "1"));
            T.isTrue(Boolean.TRUE.equals(rb.get("success")), "回滚成功");
            T.eq("1", castData(rb).get("active_version"), "回到 v1");
            Map<String, Object> bad = facade.publish(Map.of("ac_json",
                    "{\"name\":\"t\",\"version\":\"3\",\"steps\":[{\"id\":\"s1\",\"block\":\"nope\"}]}"));
            T.isTrue(Boolean.FALSE.equals(bad.get("success")), "引用不存在的积木拒绝");
            T.contains(String.valueOf(bad.get("message")), "校验不通过", "说明校验");
        });

        T.test("门面：没挂版本库时 publish/approve/rollback 回拒", () -> {
            Fake.resetCalls();
            Fake.Registry blocks = new Fake.Registry().add(Fake.fixed("ok", Map.of()));
            AcxRunner r = runner(blocks).build();
            AcxFacade facade = new AcxFacade(new AcxSessionManager(r, Runnable::run), name -> null, blocks, null);
            T.isTrue(Boolean.FALSE.equals(facade.publish(Map.of("ac_json", "{}")).get("success")), "publish 拒");
            T.isTrue(Boolean.FALSE.equals(facade.approve(Map.of("name", "t", "version", "1")).get("success")), "approve 拒");
            T.isTrue(Boolean.FALSE.equals(facade.rollback(Map.of("name", "t", "version", "1")).get("success")), "rollback 拒");
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castData(Map<String, Object> resp) {
        return (Map<String, Object>) resp.get("data");
    }


/** 22 组：真机实测形状 —— 数组下标 / $take 投影 / 按 AC 配 limits */
    private static void measuredNumenLib() {
        T.group("22 真机对齐：matches[] 下标 / $take 投影 / 按 AC 限额");

        AcxLoader.LoadReport rep = loadRealLib();

        // 真机 scan_blocks 输出形状：{matches:[{x,y,z,block,distance}]}（不是一个扁平 count/target_absX）
        List<Map<String, Object>> ore = List.of(
                Map.of("x", 101, "y", 16, "z", -840, "block", "minecraft:iron_ore", "distance", 9.5),
                Map.of("x", 2, "y", 20, "z", 4, "block", "minecraft:diamond_ore", "distance", 3.0),
                Map.of("x", 5, "y", 21, "z", 6, "block", "minecraft:iron_ore", "distance", 5.0));
        Map<String, Object> selfStatus = Fake.params("hp", 20, "position", Map.of("x", 1, "y", 2, "z", 3));

        T.test("resolvePath 支持数组下标（$scan.matches.0.block 的地基）", () -> {
            eq("minecraft:iron_ore", AcxValueResolver.resolvePath(ore, "0.block"), "列表第 0 项的字段");
            eq(101, AcxValueResolver.resolvePath(ore.get(0), "x"), "Map 路径");
            T.isNull(AcxValueResolver.resolvePath(ore, "0.nope"), "取不到返回 null");
        });

        T.test("$filter + $pick nearest + $take 把 matches[] 投影成 x/y/z 标量", () -> {
            Fake.resetCalls();
            Fake.Registry exec = new Fake.Registry()
                    .add(Fake.fixed("get_self_status", selfStatus))
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", ore)))
                    .add(Fake.fixed("inspect_block", Fake.params("block", "minecraft:iron_ore")));
            AcxRunner r = AcxRunner.builder().tools(exec).catalog(rep.catalog()).build();
            AcxRunRecord rec = r.run(rep.registered().get("ore_scan_inspect"), Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            Map<String, Object> p = Fake.calls.lastParams("inspect_block");
            eq(5, p.get("x"), "★ 选中的是离自己最近的铁矿 x（不是数组第一项 101）");
            eq(21, p.get("y"), "最近铁矿 y");
            eq(6, p.get("z"), "最近铁矿 z");
            T.isTrue(p.get("x") instanceof Integer, "投影后是标量 int");
        });

        T.test("do_while_scan：条件恒假也先跑一轮，跑满 max_iters → PAUSED 且断点停在 while", () -> {
            Fake.resetCalls();
            Fake.Registry exec = new Fake.Registry()
                    .add(Fake.fixed("get_self_status", selfStatus))
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", ore)));
            AcxRunner r = AcxRunner.builder().tools(exec).catalog(rep.catalog()).build();
            AcxRunRecord rec = r.run(rep.registered().get("do_while_scan"), Map.of());
            eq(AcxStatus.PAUSED, rec.status(), "状态");
            eq(1, rec.completedStepIndex(), "★ 断点停在 while 自己（index 1），后面的 tail 没跑");
            eq(3, Fake.calls.count("scan_blocks"), "1 次首扫 + 2 轮循环体");
        });

        T.test("ignore_failure_demo：craft 失败被吞，AC 照样 SUCCESS", () -> {
            Fake.resetCalls();
            Fake.Registry exec = new Fake.Registry()
                    .add(Fake.fixed("get_self_status", selfStatus))
                    .add(Fake.failing("craft", "没有这个物品"));
            AcxRunner r = AcxRunner.builder().tools(exec).catalog(rep.catalog()).build();
            AcxRunRecord rec = r.run(rep.registered().get("ignore_failure_demo"), Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(2, Fake.calls.count("get_self_status"), "失败步被跳过，两步读状态都跑到了");
        });

        T.test("subac_nesting：子 AC 委托真跑（catalog 命中）", () -> {
            Fake.resetCalls();
            Fake.Registry exec = new Fake.Registry()
                    .add(Fake.fixed("get_self_status", selfStatus))
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", ore)))
                    .add(Fake.fixed("inspect_block", Fake.params("block", "minecraft:iron_ore")));
            AcxRunner r = AcxRunner.builder().tools(exec).catalog(rep.catalog()).build();
            AcxRunRecord rec = r.run(rep.registered().get("subac_nesting"), Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(1, Fake.calls.count("inspect_block"), "子 AC 里的 inspect_block 被调用一次");
        });

        T.test("★ 按 AC 配 limits：max_timeout_ms 极小 → TIMEOUT（不是 FAIL 不是 PAUSED）", () -> {
            Fake.Registry exec = new Fake.Registry().add(slowTool(60));
            AcxDefinition d = AcxLoader.parseJson("""
                    {"name":"to","version":"1",
                     "limits":{"max_timeout_ms":100,"max_steps":1000},
                     "steps":[{"id":"a","block":"slow"},{"id":"b","block":"slow"},
                              {"id":"c","block":"slow"},{"id":"d","block":"slow"}]}
                    """, exec);
            AcxRunRecord rec = AcxRunner.builder().tools(exec).build().run(d, Map.of());
            eq(AcxStatus.TIMEOUT, rec.status(), "状态");
            T.isTrue(rec.completedStepIndex() < 4, "超时后不再往下走: " + rec.completedStepIndex());
        });

        T.test("没有 limits 字段时用全局限额（不会被新字段误伤）", () -> {
            Fake.Registry exec = new Fake.Registry().add(slowTool(60));
            AcxDefinition d = AcxLoader.parseJson("""
                    {"name":"g","version":"1",
                     "steps":[{"id":"a","block":"slow"},{"id":"b","block":"slow"},{"id":"c","block":"slow"}]}
                    """, exec);
            AcxRunner r = AcxRunner.builder().tools(exec)
                    .limits(new AcxRuntimeLimits(500, 20_000L, 8)).build();
            eq(AcxStatus.SUCCESS, r.run(d, Map.of()).status(), "3×60ms 在 20s 预算内跑完");
        });

        T.test("loader 拒 max_timeout_ms<=0（会让熔断立即触发）", () -> {
            Fake.Registry exec = new Fake.Registry().add(slowTool(1));
            T.throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"block\":\"slow\"}],"
                            + "\"limits\":{\"max_timeout_ms\":0}}", exec));
        });

        T.test("loader 拒未知 limits 键（只认 max_steps / max_timeout_ms / max_depth）", () -> {
            Fake.Registry exec = new Fake.Registry().add(slowTool(1));
            T.throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"x\",\"steps\":[{\"id\":\"a\",\"block\":\"slow\"}],"
                            + "\"limits\":{\"nope\":1}}", exec));
        });

        T.test("★ 可见演示 mine_nearest_ore：扫→选最近→移动→挖 一条龙", () -> {
            Fake.resetCalls();
            Fake.Registry exec = new Fake.Registry()
                    .add(Fake.fixed("get_self_status", selfStatus))
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", ore)))
                    .add(Fake.fixed("goto", Fake.params("reached", true)))
                    .add(Fake.fixed("mine", Fake.params("gathered", 1, "count", 1)));
            AcxRunner r = AcxRunner.builder().tools(exec).catalog(rep.catalog()).build();
            AcxRunRecord rec = r.run(rep.registered().get("mine_nearest_ore"), Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(6, rec.completedStepIndex(), "六步全过");
            Map<String, Object> g = Fake.calls.lastParams("goto");
            eq(5, g.get("x"), "★ goto 拿的是最近铁矿 x（自己位置是 1,2,3）");
            eq(21, g.get("y"), "最近铁矿 y");
            eq(6, g.get("z"), "最近铁矿 z");
            eq("minecraft:iron_ore", g.get("block"), "带方块名让 goto 认目标");
            eq(1, Fake.calls.count("mine"), "挖了一次");
            T.isTrue(Fake.calls.lastParams("mine").get("count") instanceof Number
                    && ((Number) Fake.calls.lastParams("mine").get("count")).intValue() == 1,
                    "count=1 传下去了");
            T.isTrue(!g.containsKey("ignore_failure"), "控制键不下发");
        });

        T.test("只差 limits 的两个定义指纹不同（改限额等于换版本）", () -> {
            Fake.Registry exec = new Fake.Registry().add(slowTool(1));
            String steps = "\"steps\":[{\"id\":\"a\",\"block\":\"slow\"}]";
            AcxDefinition a = AcxLoader.parseJson(
                    "{\"name\":\"x\"," + steps + ",\"limits\":{\"max_timeout_ms\":100}}", exec);
            AcxDefinition b = AcxLoader.parseJson(
                    "{\"name\":\"x\"," + steps + ",\"limits\":{\"max_timeout_ms\":200}}", exec);
            T.isTrue(!AcxFingerprint.of(a).equals(AcxFingerprint.of(b)), "指纹应不同");
        });
    }

    /** 按真机实测目录造夹具：16 个 Numen 工具端口（字段声明来自 NumenToolCatalog） */
    private static Fake.Registry numenRegistry() {
        return numenRegistryExcept();
    }

    /** 同上，但跳过指定工具（留给 AcxHostBridge.registerAll 接管，避免「注册表已有」被跳过）。 */
    private static Fake.Registry numenRegistryExcept(String... skip) {
        java.util.Set<String> excluded = new java.util.HashSet<>(List.of(skip));
        Fake.Registry reg = new Fake.Registry();
        for (NumenToolCatalog.ToolSpec spec : NumenToolCatalog.core()) {
            if (!excluded.contains(spec.name())) {
                reg.add(portStub(spec));
            }
        }
        return reg;
    }

    private static AcxTool portStub(NumenToolCatalog.ToolSpec spec) {
        return new AcxTool() {
            @Override public String name() { return spec.name(); }
            @Override public AcxToolSchema schema() { return portSchema(spec.schema()); }
            @Override public AcxStepOutcome execute(Map<String, Object> p, AcxCallContext c) {
                return AcxStepOutcome.success(Fake.params("ok", true));
            }
        };
    }

    private static AcxToolSchema portSchema(AcxPortSchema s) {
        Map<String, AcxToolSchema.ParamType> params = new LinkedHashMap<>();
        for (Map.Entry<String, AcxPortSchema.Param> e : s.params().entrySet()) {
            params.put(e.getKey(), portType(e.getValue().type()));
        }
        for (String f : s.outputFields()) {
            params.putIfAbsent(f, AcxToolSchema.ParamType.ANY);
        }
        return new AcxToolSchema(params, s.required());
    }

    private static AcxToolSchema.ParamType portType(AcxPortSchema.Type t) {
        switch (t) {
            case STRING: return AcxToolSchema.ParamType.STRING;
            case INTEGER: return AcxToolSchema.ParamType.INTEGER;
            case NUMBER: return AcxToolSchema.ParamType.NUMBER;
            case BOOLEAN: return AcxToolSchema.ParamType.BOOLEAN;
            default: return AcxToolSchema.ParamType.ANY;
        }
    }

    /** 加载真实 ac-lib（16 端口夹具 + strict），找不到目录直接炸 */
    private static AcxLoader.LoadReport loadRealLib() {
        Path lib = acLib();
        if (lib == null) {
            throw new AssertionError("找不到 ac-lib（工作目录=" + Path.of(".").toAbsolutePath() + "）");
        }
        return AcxLoader.loadAll(lib, numenRegistry(), false, true);
    }

    /** 慢积木：用来把 wall-clock 熔断逼出来 */
    private static AcxTool slowTool(long millis) {
        return new AcxTool() {
            @Override public String name() { return "slow"; }
            @Override public AcxToolSchema schema() {
                return new AcxToolSchema(new LinkedHashMap<>(), java.util.Set.of());
            }
            @Override public AcxStepOutcome execute(Map<String, Object> p, AcxCallContext c) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AcxStepOutcome.success(Fake.params("n", 1));
            }
        };
    }

    /** 23 组：真机暴露的门面/版本库硬化（B7）*/
    private static void facadeHardening() {
        T.group("23 真机硬化：观测接口容错 + 版本库安全查询");

        T.test("★ acx_library 遇「只有未批准版本」的 AC 不炸（观测接口不该整体失败）", () -> {
            Path d = tmpDir();
            AcxRegistryLike blocks = null;
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("library.json"), reg);
            AcxDefinition ok = AcxLoader.parseJson(
                    "{\"name\":\"approved\",\"version\":\"1\",\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}",
                    reg);
            lib.publish(ok, "");
            lib.approve("approved", "1", "t", "");
            AcxDefinition pend = AcxLoader.parseJson(
                    "{\"name\":\"waiting\",\"version\":\"1\",\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}",
                    reg);
            lib.publish(pend, "");
            AcxSessionManager sm = new AcxSessionManager(
                    AcxRunner.builder().tools(reg).build(), Runnable::run);
            AcxFacade f = new AcxFacade(sm, lib::active, lib::version, reg, lib);
            Map<String, Object> out = f.library(Map.of());
            eq(true, out.get("success"), "库查询本身成功（err=" + out.get("message") + "）");
            Map<?, ?> data = (Map<?, ?>) out.get("data");
            T.notNull(data.get("active"), "active 表在");
            eq("1", ((Map<?, ?>) data.get("active")).get("approved"), "已批准的有 active 版本");
            T.notNull(data.get("not_online"), "★ 未上线的单列出来而不是抛异常");
            T.isTrue(((Map<?, ?>) data.get("not_online")).containsKey("waiting"), "waiting 在 not_online 里");
        });

        T.test("hasAnyVersion 对未知名字返回 false（versions(name) 会抛，不能拿它判首次）", () -> {
            Path d = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("library.json"), reg);
            T.isTrue(!lib.hasAnyVersion("never_seen"), "没见过 → false");
            T.isTrue(!lib.isOnline("never_seen"), "没见过 → 不在线");
            AcxDefinition def = AcxLoader.parseJson(
                    "{\"name\":\"x\",\"version\":\"1\",\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}",
                    reg);
            lib.publish(def, "");
            T.isTrue(lib.hasAnyVersion("x"), "发布过 → true");
            T.isTrue(!lib.isOnline("x"), "只发布未批准 → 还不在线");
            lib.approve("x", "1", "t", "");
            T.isTrue(lib.isOnline("x"), "批准后 → 在线");
        });

        T.test("未批准的 AC 按名执行仍被拒（自动批准那道闸只对 jar 自带脚本开）", () -> {
            Path d = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("library.json"), reg);
            AcxDefinition def = AcxLoader.parseJson(
                    "{\"name\":\"y\",\"version\":\"1\",\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}",
                    reg);
            lib.publish(def, "");
            AcxSessionManager sm = new AcxSessionManager(
                    AcxRunner.builder().tools(reg).build(), Runnable::run);
            AcxFacade f = new AcxFacade(sm, lib::active, lib::version, reg, lib);
            Map<String, Object> out = f.execute(Map.of("ac_name", "y", "input", Map.of()));
            eq(false, out.get("success"), "未批准不得执行");
            String msg = String.valueOf(out.get("message"));
            T.isTrue(msg.contains("批准"), "拒绝理由要说清要人工批准: " + msg);
        });
    }

    /** 占位类型：让上面那段看起来像在用 registry（真实用 Fake.Registry） */
    private interface AcxRegistryLike {
    }


    /** 24 组：子 AC 委托能不能发布（真机实测：版本库与 loader 判据不一致）*/
    private static void subAcPublishing() {
        T.group("24 子 AC 委托的发布判据");

        Path d = tmpDir();
        Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
        FileAcxLibrary lib = new FileAcxLibrary(d.resolve("library.json"), reg);
        String child = "{\"name\":\"child\",\"version\":\"1\",\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}";
        String parent = "{\"name\":\"parent\",\"version\":\"1\",\"steps\":["
                + "{\"id\":\"a\",\"block\":\"get_self_status\"},"
                + "{\"id\":\"delegated\",\"block\":\"child\"}]}";

        T.test("没给 AC 名单时，委托子 AC 会被判「既不是积木也不是 AC」", () -> {
            AcxDefinition p1 = AcxLoader.parseJson(parent, reg);
            List<String> problems = lib.validate(p1);
            T.isTrue(problems.stream().anyMatch(x -> x.contains("child")),
                    "应指出子 AC 名: " + problems);
        });

        T.test("★ 给了 AC 名单后同一份定义通过（与 loader 判据对齐）", () -> {
            AcxDefinition p1 = AcxLoader.parseJson(parent, reg);
            eq(0, lib.validate(p1, Set.of("child")).size(), "名单里有 child 就该过");
            String v = lib.publish(p1, "", Set.of("child"));
            lib.approve("parent", v, "t", "");
            T.isTrue(lib.isOnline("parent"), "带子 AC 的 AC 能上线");
        });

        T.test("名单里没有的子 AC 仍然被拒（不能变成「什么都能发布」）", () -> {
            AcxDefinition p2 = AcxLoader.parseJson(
                    parent.replace("\"child\"", "\"not_there\""), reg);
            T.isTrue(lib.validate(p2, Set.of("child")).stream().anyMatch(x -> x.contains("not_there")),
                    "未知引用仍要报错");
        });

        T.test("acx_publish 门面走的是带名单那条路（门面自带 library.names）", () -> {
            Fake.Registry reg2 = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
            FileAcxLibrary lib2 = new FileAcxLibrary(tmpDir().resolve("lib2.json"), reg2);
            AcxSessionManager sm = new AcxSessionManager(
                    AcxRunner.builder().tools(reg2).build(), Runnable::run);
            AcxFacade f = new AcxFacade(sm, lib2::active, lib2::version, reg2, lib2);
            Map<String, Object> r1 = f.publish(Map.of("ac_json",
                    "{\"name\":\"child\",\"version\":\"1\",\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}"));
            eq(true, r1.get("success"), "先发子 AC: " + r1.get("message"));
            Map<String, Object> r2out = f.publish(Map.of("ac_json", parent));
            eq(true, r2out.get("success"), "再发父 AC（引用已存在的子 AC）: " + r2out.get("message"));
        });
    }


    /** 25 组：版本库往返不能丢字段（真机踩过：limits/preconditions 被手工重建吃掉）*/
    private static void versionStoreRoundTrip() {
        T.group("25 版本库往返不丢字段");

        Path d = tmpDir();
        Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
        String json = "{\"name\":\"rich\",\"version\":\"1\","
                + "\"preconditions\":[{\"field\":\"$input.ready\",\"op\":\"==\",\"value\":true}],"
                + "\"limits\":{\"max_timeout_ms\":321,\"max_steps\":4321},"
                + "\"planner_notes\":\"notes-planner\",\"safety_notes\":\"notes-safety\","
                + "\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}";

        T.test("★ publish → persist → load 之后 limits / preconditions 还在（真机丢过）", () -> {
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("lib.json"), reg);
            AcxDefinition def = AcxLoader.parseJson(json, reg);
            lib.publish(def, "n");
            lib.approve("rich", "1", "t", "");
            lib.persist();

            FileAcxLibrary again = new FileAcxLibrary(d.resolve("lib.json"), reg);
            again.load();
            AcxDefinition back = again.active("rich");
            T.notNull(back.limits(), "limits 还在");
            eq(321L, back.limits().maxTimeoutMs(), "max_timeout_ms 往返不变");
            eq(4321, back.limits().maxSteps(), "max_steps 往返不变");
            eq(1, back.preconditions().size(), "preconditions 还在");
            eq("notes-planner", back.plannerNotes(), "planner_notes 还在");
            eq("notes-safety", back.safetyNotes(), "safety_notes 还在");
        });

        T.test("★ 往返之后按名执行真的吃 limits（不是回落全局 200 步）", () -> {
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("lib.json"), reg);
            lib.load();
            AcxDefinition back = lib.active("rich");
            T.isTrue(back.limits().maxSteps() > 200, "限额来自定义本身: " + back.limits().maxSteps());
            T.isTrue(AcxRuntimeLimits.defaults().maxSteps() != back.limits().maxSteps(),
                    "确实不是全局默认值");
        });

        T.test("旧存档（无 limits / preconditions 键）仍能读出来", () -> {
            Path d2 = tmpDir();
            try {
                Files.writeString(d2.resolve("legacy.json"),
                        "{\"defs\":{\"old\":{\"active_version\":\"1\",\"versions\":{\"1\":{"
                                + "\"definition\":{\"name\":\"old\",\"version\":\"1\","
                                + "\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]},"
                                + "\"status\":\"STABLE\"}}}}}",
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            FileAcxLibrary lib = new FileAcxLibrary(d2.resolve("legacy.json"), reg);
            lib.load();
            AcxDefinition old = lib.active("old");
            T.notNull(old.limits(), "没有 limits 键 → 空规格而不是崩");
            T.isTrue(old.limits().isEmpty(), "空规格");
            eq(0, old.preconditions().size(), "没有 preconditions → 空");
        });
    }


    /** 26 组：端口整数数组类型（真机 attack.entity_ids 是 {type:array, items:{type:integer}}） */
    private static void intArrayPort() {
        T.group("26 端口类型：INT_ARRAY（真机 schema 是整数数组，不是单个整数）");

        T.test("合法整数数组通过校验", () -> {
            AcxPortSchema sch = AcxPortSchema.builder()
                    .param("ids", AcxPortSchema.Param.opt(AcxPortSchema.Type.INT_ARRAY).range(1, 20))
                    .build();
            eq(0, sch.validate(Map.of("ids", List.of(1, 2))).size(), "两元素合法");
            eq(0, sch.validate(Map.of()).size(), "可空字段可省略");
        });

        T.test("元素非整数被拒（半截小数 / 字符串）", () -> {
            AcxPortSchema sch = AcxPortSchema.builder()
                    .param("ids", AcxPortSchema.Param.opt(AcxPortSchema.Type.INT_ARRAY))
                    .build();
            T.isTrue(sch.validate(Map.of("ids", List.of(1, 2.5))).stream()
                    .anyMatch(x -> x.contains("元素应为整数")), "2.5 被拒");
            T.isTrue(sch.validate(Map.of("ids", List.of(1, "a"))).stream()
                    .anyMatch(x -> x.contains("元素应为整数")), "字符串被拒");
        });

        T.test("标量被拒（不能像 int_array 绑定那样自动包成单元素）", () -> {
            AcxPortSchema sch = AcxPortSchema.builder()
                    .param("ids", AcxPortSchema.Param.opt(AcxPortSchema.Type.INT_ARRAY))
                    .build();
            T.isTrue(sch.validate(Map.of("ids", 3178)).stream()
                    .anyMatch(x -> x.contains("应为整数数组")), "标量被拒");
        });

        T.test("range 解释为长度上下限（1..20）", () -> {
            AcxPortSchema sch = AcxPortSchema.builder()
                    .param("ids", AcxPortSchema.Param.opt(AcxPortSchema.Type.INT_ARRAY).range(1, 20))
                    .build();
            T.isTrue(sch.validate(Map.of("ids", List.of())).stream()
                    .anyMatch(x -> x.contains("至少")), "空数组被拒");
            List<Integer> tooMany = new java.util.ArrayList<>();
            for (int i = 0; i < 21; i++) {
                tooMany.add(i);
            }
            T.isTrue(sch.validate(Map.of("ids", tooMany)).stream()
                    .anyMatch(x -> x.contains("至多")), "21 个被拒");
            eq(0, sch.validate(Map.of("ids", tooMany.subList(0, 20))).size(), "整 20 个合法");
        });

        T.test("★ 真机 attack 端口：鸡 id 3178 通过、标量仍被拒", () -> {
            AcxPortSchema attack = NumenToolCatalog.find("attack").schema();
            eq(0, attack.validate(Map.of("entity_ids", List.of(3178))).size(),
                    "整数组应通过: " + attack.validate(Map.of("entity_ids", List.of(3178))));
            T.isTrue(attack.validate(Map.of("entity_ids", 3178)).stream()
                    .anyMatch(x -> x.contains("应为整数数组")), "标量仍被拒");
        });
    }

    /** 27 组：门面键名一致性（真机踩到：execute 用 ac_name，approve/rollback 只认 name） */
    private static void facadeKeyAlias() {
        T.group("27 门面键名：ac_name 是 name 的别名");

        T.test("★ approve 只给 ac_name 也能过（修好之前真机被拒：需要 name 与 version）", () -> {
            Path d = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("library.json"), reg);
            AcxDefinition def = AcxLoader.parseJson(
                    "{\"name\":\"alias_demo\",\"version\":\"1\","
                            + "\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}",
                    reg);
            lib.publish(def, "");
            AcxSessionManager sm = new AcxSessionManager(
                    AcxRunner.builder().tools(reg).build(), Runnable::run);
            AcxFacade f = new AcxFacade(sm, lib::active, lib::version, reg, lib);
            Map<String, Object> out = f.approve(Map.of("ac_name", "alias_demo", "version", "1"));
            eq(true, out.get("success"), "ac_name 应被认（err=" + out.get("message") + "）");
            T.isTrue(lib.isOnline("alias_demo"), "批准后已上线");
            eq("1", String.valueOf(lib.activeVersion("alias_demo")), "active=v1");
        });

        T.test("approve/rollback 仍认老键 name（向后兼容不能破）", () -> {
            Path d = tmpDir();
            Fake.Registry reg = new Fake.Registry().add(Fake.fixed("get_self_status", Fake.params("hp", 20)));
            FileAcxLibrary lib = new FileAcxLibrary(d.resolve("library.json"), reg);
            AcxDefinition def = AcxLoader.parseJson(
                    "{\"name\":\"old_key\",\"version\":\"1\","
                            + "\"steps\":[{\"id\":\"a\",\"block\":\"get_self_status\"}]}",
                    reg);
            lib.publish(def, "");
            lib.publish(def, "");
            AcxSessionManager sm = new AcxSessionManager(
                    AcxRunner.builder().tools(reg).build(), Runnable::run);
            AcxFacade f = new AcxFacade(sm, lib::active, lib::version, reg, lib);
            eq(true, f.approve(Map.of("name", "old_key", "version", "1")).get("success"), "老键仍可用");
            eq(true, f.rollback(Map.of("ac_name", "old_key", "version", "1")).get("success"),
                    "rollback 也认 ac_name");
        });
    }

private static void takeDiag() {
        T.group("29 $take 语义与诊断（真机打鸡撞出来的）");

        // 真机形状：scan_nearby_entities 的元素把 x/y/z 藏在 position 里（scan_blocks 的 matches 是平铺的）
        Map<String, Object> chicken = new LinkedHashMap<>();
        chicken.put("id", 30);
        chicken.put("type", "entity.minecraft.chicken");
        chicken.put("position", Map.of("x", 939.9, "y", 60.0, "z", 793.5));
        Map<String, Object> find = new LinkedHashMap<>();
        find.put("entities", new java.util.ArrayList<>(List.of(chicken)));
        Map<String, Map<String, Object>> outs = Map.of("find", find);

        T.test("★ $take 只在 $pick 选出单个元素后生效；点路径能进嵌套", () -> {
            java.util.List<AcxParamBinder.Warning> w = new java.util.ArrayList<>();
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params("x", Fake.params(
                            "$from", "$find.entities",
                            "$filter", Fake.params("field", "type", "op", "==",
                                    "value", "entity.minecraft.chicken"),
                            "$pick", "first",
                            "$take", "position.x")),
                    Map.of(), Map.of(), outs, w);
            eq(0, w.size(), "不该有警告: " + w);
            eq(939.9, out.get("x"), "从 position.x 取到坐标");
        });

        T.test("★ 平铺取不到时：reason 列出实际可用键 + hint 直接教点路径（原来只说『值类型 X』）", () -> {
            java.util.List<AcxParamBinder.Warning> w = new java.util.ArrayList<>();
            AcxParamBinder.bind(
                    Fake.params("x", Fake.params(
                            "$from", "$find.entities",
                            "$pick", "first",
                            "$take", "x")),
                    Map.of(), Map.of(), outs, w);
            eq(1, w.size(), "恰好一条诊断: " + w);
            T.contains(w.get(0).reason(), "position", "reason 列出可用键");
            T.contains(w.get(0).hint(), "position.x", "hint 直接给出正确写法");
            T.contains(w.get(0).hint(), "$pick", "hint 提醒要先 $pick 出单个元素");
        });

        T.test("多字段投影：$take:[\"position.x\",\"position.z\"] 出 Map", () -> {
            java.util.List<AcxParamBinder.Warning> w = new java.util.ArrayList<>();
            Map<String, Object> out = AcxParamBinder.bind(
                    Fake.params("at", Fake.params(
                            "$from", "$find.entities",
                            "$pick", "first",
                            "$take", List.of("position.x", "position.z"))),
                    Map.of(), Map.of(), outs, w);
            eq(0, w.size(), "不该有警告: " + w);
            Map<?, ?> at = (Map<?, ?>) out.get("at");
            eq(939.9, at.get("position.x"), "投影 x");
            eq(793.5, at.get("position.z"), "投影 z");
        });
    }

/** 第 30 组：可变量 + 写回步（AC-B9）—— 解开「跨断点引用不可用」的前提 */
    private static void varsAndWriteback() {
        T.group("30 可变量 + 写回步（set / $var / 跨断点存活）");

        Fake.Registry reg = new Fake.Registry()
                .add(Fake.fixed("read", Fake.params("position", Map.of("x", 1, "y", 2, "z", 3))))
                .add(Fake.fixed("echo", Fake.params("ok", true)))
                .add(Fake.zero("tick"));

        T.test("set 写入后下一步 $var.x 能读到", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("w", "set", Fake.params("n", 7)),
                    step("say", "echo", Fake.params("v", "$var.n")));
            AcxRunRecord rec = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(7, Fake.calls.lastParams("echo").get("v"), "echo 收到变量值");
            eq(7, rec.vars().get("n"), "记录里带变量快照");
            eq(7, rec.toMap().get("vars") == null ? null : ((java.util.Map<?, ?>) rec.toMap().get("vars")).get("n"),
                    "toMap 也带 vars");
        });

        T.test("set 的值支持绑定描述符（$from + $take 点路径）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("r", "read", Map.of()),
                    step("w", "set", Fake.params("px", Fake.params("$from", "$r.position", "$take", "x"))),
                    step("say", "echo", Fake.params("v", "$var.px")));
            AcxRunRecord rec = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(1, Fake.calls.lastParams("echo").get("v"), "描述符取到的值写进了变量");
        });

        T.test("★ 未知名 $var.x 是 FAIL 记录，不是崩掉（响亮失败）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("say", "echo", Fake.params("v", "$var.nope")));
            AcxRunRecord rec = runner(reg).build().run(d, Map.of());
            eq(AcxStatus.FAIL, rec.status(), "状态");
            T.contains(String.valueOf(rec.errorMessage()), "变量未定义", "错误要说清是哪个变量没定义");
            eq("say", rec.failedStep(), "失败步骤");
        });

        T.test("★ resume 后变量仍在（这正是真机限制 L-06 的解法）", () -> {
            Fake.resetCalls();
            Fake.PauseOnce once = new Fake.PauseOnce();
            Fake.Registry r2 = new Fake.Registry()
                    .add(Fake.fixed("echo", Fake.params("ok", true)))
                    .add(Fake.pauserOnce("wait", once, "等一下"));
            AcxDefinition d = def(
                    step("w", "set", Fake.params("n", 42)),
                    step("wait", "wait", Map.of()),
                    step("say", "echo", Fake.params("v", "$var.n")));
            AcxRunner r = runner(r2).build();
            AcxRunRecord first = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, first.status(), "第一次跑到 wait 暂停");
            eq(42, first.vars().get("n"), "暂停记录里变量已落盘");
            AcxRunRecord second = r.resume(d, first);
            eq(AcxStatus.SUCCESS, second.status(), "续跑到完成（err=" + second.errorMessage() + "）");
            eq(42, Fake.calls.lastParams("echo").get("v"), "★ 跨断点仍读到变量");
        });

        T.test("控制块里也能写变量（if 分支里 set 之后同层可读）", () -> {
            Fake.resetCalls();
            FakeRegistryHolder h = new FakeRegistryHolder();
            AcxDefinition d = def(
                    ctrl("gate", "if", Fake.params("conditions", List.of(Fake.cond("$input.go", "==", true))),
                            step("w", "set", Fake.params("tag", "in"))),
                    step("say", "echo", Fake.params("v", "$var.tag")));
            AcxRunRecord rec = runner(reg).build().run(d, Map.of("go", true));
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq("in", Fake.calls.lastParams("echo").get("v"), "分支里写的变量在外面能读");
            eq(null, h.unused(), "");
        });

        T.test("loader：set 不查积木表（空注册表也能加载）+ $var 不被误判", () -> {
            Path d2 = tmpDir();
            write(d2.resolve("v.ac"), """
                    {"name":"v","steps":[
                      {"id":"w","block":"set","params":{"n":"$input.n"}},
                      {"id":"s","block":"echo","params":{"v":"$var.n"}}
                    ]}
                    """);
            Fake.Registry empty = new Fake.Registry().add(Fake.fixed("echo", Fake.params("ok", true)));
            AcxLoader.LoadReport r2 = AcxLoader.loadAll(d2, empty, false, true);
            T.isTrue(r2.registered().containsKey("v"),
                    "空积木表也该注册（错误: " + r2.errors() + "）");
        });

        T.test("loader：set 不接受 children / params 不能空", () -> {
            T.throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"x\",\"steps\":[{\"id\":\"w\",\"block\":\"set\","
                            + "\"params\":{\"n\":1},\"children\":[{\"id\":\"a\",\"block\":\"echo\"}]}]}",
                    new Fake.Registry()));
            T.throwsA(IllegalArgumentException.class, () -> AcxLoader.parseJson(
                    "{\"name\":\"x\",\"steps\":[{\"id\":\"w\",\"block\":\"set\",\"params\":{}}]}",
                    new Fake.Registry()));
        });

        T.test("★ 直接调 resolver：$var 未知名抛异常，带点路径能下钻", () -> {
            AcxValueResolver res = new AcxValueResolver(
                    Map.of(), Map.of(), Map.of(), Fake.params("pos", Fake.params("x", 9)));
            eq(9, res.resolve("$var.pos.x"), "点路径能读到");
            T.throwsA(IllegalArgumentException.class, () -> res.resolve("$var.zzz"));
        });
    }

    /** 占位辅助（保持测试组结构清晰，不参与逻辑）。 */
    private static final class FakeRegistryHolder {
        Object unused() {
            return null;
        }
    }

/** 31 组：AC-B10 算术层（$calc / $len / $loop）+ 变量驱动循环 */
    private static void calcAndLoop() {
        T.group("31 算术层：$calc / $len / $loop 与变量驱动循环");

        Fake.Registry reg = new Fake.Registry()
                .add(Fake.fixed("echo", Fake.params("seen", 1)))
                .add(Fake.fixed("src", Fake.params("n", 7, "pos", Fake.params("x", 10, "y", 64, "z", -3))));

        // Fake.calls.lastParams 只留最后一次调用 → 每条表达式各跑一条 AC，逐条断言
        String[][] cases = {{"1 + 2 * 3", "7"}, {"(10 - 4) / 3", "2"}, {"7 % 4", "3"},                {"-(2 + 3)", "-5"}, {"10 / 4", "2.5"}};
        for (String[] cs : cases) {
            Fake.resetCalls();
            AcxDefinition one = def(step("v", "echo", Fake.params("val", Fake.params("$calc", cs[0]))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(one, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（" + cs[0] + " err=" + rec.errorMessage() + "）");
            Object got = Fake.calls.lastParams("echo").get("val");
            eq(cs[1], String.valueOf(got), "$calc " + cs[0] + "（整数值回 Long，小数回 Double）");
        }
        Fake.resetCalls();
        AcxDefinition wi = def(step("v", "echo", Fake.params("val", Fake.params("$calc", "1 + 2 * 3"))));
        AcxRunner.builder().tools(reg).build().run(wi, Map.of());
        T.isTrue(Fake.calls.lastParams("echo").get("val") instanceof Long, "整数值回 Long（不是 Double）");


        T.test("$calc：能引用 $var / $prev / $stepId（同一套引用语义）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("read", "src", Map.of()),
                    step("calc", "echo", Fake.params("v", Fake.params("$calc", "$read.pos.x + $read.n * 2"))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(24L, Fake.calls.lastParams("echo").get("v"), "10 + 7*2 = 24");
        });

        T.test("$calc：函数 max/min/abs/floor/ceil/round/sqrt/pow/dist", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(step("f", "echo", Fake.params(
                    "mx", Fake.params("$calc", "max(3, 9)"),
                    "mn", Fake.params("$calc", "min(3, 9)"),
                    "ab", Fake.params("$calc", "abs(-5)"),
                    "fl", Fake.params("$calc", "floor(2.9)"),
                    "ce", Fake.params("$calc", "ceil(2.1)"),
                    "ro", Fake.params("$calc", "round(2.5)"),
                    "sq", Fake.params("$calc", "sqrt(16)"),
                    "pw", Fake.params("$calc", "pow(2, 10)"),
                    "ds", Fake.params("$calc", "dist(0, 0, 0, 3, 4, 0)"))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            Map<String, Object> p = Fake.calls.lastParams("echo");
            eq(9L, p.get("mx"), "max");
            eq(3L, p.get("mn"), "min");
            eq(5L, p.get("ab"), "abs");
            eq(2L, p.get("fl"), "floor");
            eq(3L, p.get("ce"), "ceil");
            eq(3L, p.get("ro"), "round(2.5)");
            eq(4L, p.get("sq"), "sqrt(16)");
            eq(1024L, p.get("pw"), "pow(2,10)");
            eq(5L, p.get("ds"), "dist 3-4-5");
        });

        T.test("$len：数列表/映射长度（不用 for 也能数个数）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("scan", "src", Map.of()),
                    step("n", "echo", Fake.params("howmany", Fake.params("$len", "$scan.pos"))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(3L, Fake.calls.lastParams("echo").get("howmany"), "pos 有 3 个键");
        });

        T.test("$calc：除零 / 未知函数 / 引用不是数字 → 响亮失败（不返回 0 假装成功）", () -> {
            AcxDefinition dz = def(step("x", "echo", Fake.params("v", Fake.params("$calc", "5 / 0"))));
            AcxRunRecord r1 = AcxRunner.builder().tools(reg).build().run(dz, Map.of());
            eq(AcxStatus.FAIL, r1.status(), "除零应 FAIL");
            T.isTrue(String.valueOf(r1.errorMessage()).contains("除零"), "错误要说清除零: " + r1.errorMessage());

            AcxDefinition uf = def(step("x", "echo", Fake.params("v", Fake.params("$calc", "nope(2)"))));
            AcxRunRecord r2 = AcxRunner.builder().tools(reg).build().run(uf, Map.of());
            eq(AcxStatus.FAIL, r2.status(), "未知函数应 FAIL");
            T.isTrue(String.valueOf(r2.errorMessage()).contains("nope"), "错误要指出函数名: " + r2.errorMessage());

            AcxDefinition bad = def(
                    step("read", "src", Map.of()),
                    step("x", "echo", Fake.params("v", Fake.params("$calc", "$read.pos + 1"))));
            AcxRunRecord r3 = AcxRunner.builder().tools(reg).build().run(bad, Map.of());
            eq(AcxStatus.FAIL, r3.status(), "对映射做算术应 FAIL");
        });

        T.test("★ 变量驱动循环：条件读 $var，body 用 $calc 推进游标（挖通道的骨架）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("init", "set", Fake.params("depth", 0)),
                    ctrl("loop", "while", Fake.params(
                            "condition", Fake.cond("$var.depth", "<", 3),
                            "max_iters", 5,
                            "stagnant_limit", 5),
                            step("dig", "set", Fake.params(
                                    "depth", Fake.params("$calc", "$var.depth + 1"))),
                            step("mark", "set", Fake.params(
                                    "last_iter", "$loop.iter"))),
                    step("done", "set", Fake.params("final", "$var.depth")));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(3L, rec.vars().get("depth"), "★ 循环靠变量收敛到 3（不是靠 max_iters）");
            eq(3L, rec.vars().get("last_iter"), "$loop.iter 在 body 里可见");
            eq(3L, rec.vars().get("final"), "循环外仍能读到变量");
        });

        T.test("$loop.count / $loop.total 是别名（与 iter 同值）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("init", "set", Fake.params("n", 0)),
                    ctrl("loop", "while", Fake.params(
                            "condition", Fake.cond("$var.n", "<", 2),
                            "max_iters", 5, "stagnant_limit", 5),
                            step("bump", "set", Fake.params(
                                    "n", Fake.params("$calc", "$var.n + 1"))),
                            step("save", "set", Fake.params(
                                    "iter_seen", "$loop.iter",
                                    "count_seen", "$loop.count",
                                    "total_seen", "$loop.total"))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(2L, rec.vars().get("n"), "跑了两轮");
            eq(2L, rec.vars().get("iter_seen"), "$loop.iter");
            eq(2L, rec.vars().get("count_seen"), "$loop.count");
            eq(2L, rec.vars().get("total_seen"), "$loop.total");
        });
    }

/** 32 组：AC-B11 for 控制块（遍历 matches[] / 逐元素处理） */
    private static void forBlock() {
        T.group("32 for 控制块：遍历列表 / 元素写进变量 / 遍历完算成功");

        List<Object> matches = List.of(
                Fake.params("x", 1, "y", 2, "z", 3, "block", "minecraft:stone"),
                Fake.params("x", 4, "y", 5, "z", 6, "block", "minecraft:dirt"),
                Fake.params("x", 7, "y", 8, "z", 9, "block", "minecraft:coal_ore"));

        Fake.Registry reg = new Fake.Registry()
                .add(Fake.fixed("scan_blocks", Fake.params("matches", matches)))
                .add(Fake.fixed("mine", Fake.params("mined", 1, "count", 1)))
                .add(Fake.fixed("teleport", Fake.params("arrived", 1)));

        T.test("★ for 逐个处理 matches[]：元素写进 $var.m，遍历完 SUCCESS（非 PAUSED）", () -> {
            Fake.resetCalls();
            AcxDefinition d = def(
                    step("scan", "scan_blocks", Fake.params("block_ids", "minecraft:stone", "radius", 8)),
                    ctrl("each", "for", Fake.params("list", "$scan.matches", "as", "m"),
                            step("grab", "set", Fake.params("seen", "$var.m.block")),
                            step("last_x", "set", Fake.params("x_of_last", "$var.m.x"))),
                    step("count_seen", "set", Fake.params("howmany", Fake.params("$len", "$scan.matches"))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(7L, rec.vars().get("x_of_last"), "最后一轮 x=7");
            eq(3L, rec.vars().get("howmany"), "列表 3 条");
        });

        T.test("for 的每轮都会跑 children（矿挖了 3 次）", () -> {
            Fake.resetCalls();
            Fake.Registry r2 = new Fake.Registry()
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", matches)))
                    .add(Fake.fixed("mine", Fake.params("mined", 1, "count", 1)));
            AcxDefinition d = def(
                    step("scan", "scan_blocks", Fake.params("block_ids", "minecraft:stone", "radius", 8)),
                    ctrl("each", "for", Fake.params("list", "$scan.matches", "as", "m"),
                            step("dig", "mine", Fake.params("block_ids", "$var.m.block", "count", 1))));
            AcxRunRecord rec = AcxRunner.builder().tools(r2).build().run(d, Map.of());
            eq(AcxStatus.SUCCESS, rec.status(), "状态（err=" + rec.errorMessage() + "）");
            eq(3, Fake.calls.count("mine"), "3 条矿脉挖 3 次");
            eq("minecraft:coal_ore", Fake.calls.lastParams("mine").get("block_ids"), "最后一次是第 3 条");
        });

        T.test("for 撞显式 max_iters → PAUSED（截断等续跑，不算成功）", () -> {
            Fake.resetCalls();
            Fake.Registry r3 = new Fake.Registry()
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", matches)))
                    .add(Fake.fixed("mine", Fake.params("mined", 1, "count", 1)));
            AcxDefinition d = def(
                    step("scan", "scan_blocks", Fake.params("block_ids", "minecraft:stone", "radius", 8)),
                    ctrl("each", "for", Fake.params("list", "$scan.matches", "as", "m",
                            "max_iters", 2),
                            step("dig", "mine", Fake.params("block_ids", "$var.m.block", "count", 1))));
            AcxRunRecord rec = AcxRunner.builder().tools(r3).build().run(d, Map.of());
            eq(AcxStatus.PAUSED, rec.status(), "截断应 PAUSED");
            eq(2, Fake.calls.count("mine"), "只跑了 2 轮");
        });

        T.test("for 的 list 不是列表 → 响亮失败", () -> {
            AcxDefinition bad = def(
                    step("scan", "scan_blocks", Fake.params("block_ids", "minecraft:stone", "radius", 8)),
                    ctrl("each", "for", Fake.params("list", "$scan.matches.0.block"),
                            step("x", "set", Fake.params("v", 1))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(bad, Map.of());
            eq(AcxStatus.FAIL, rec.status(), "应 FAIL");
            T.isTrue(String.valueOf(rec.errorMessage()).contains("list 必须指向一个列表"),
                    "要说清 list: " + rec.errorMessage());
        });

        T.test("loader：for 缺 list 直接拒（写错就别进库）", () -> {
            Path d = tmpDir();
            write(d.resolve("bad_for.ac"), """
                    {"name":"bad_for","steps":[
                      {"id":"loop","block":"for","params":{"as":"m"},
                       "children":[{"id":"x","block":"set","params":{"v":1}}]}
                    ]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(d, reg, false, true);
            eq(0, rep.registered().size(), "缺 list 不该注册");
            T.isTrue(rep.errors().stream().anyMatch(e -> e.contains("list")),
                    "错误要说清缺 list: " + rep.errors());
        });

        T.test("loader：合法 for 能加载（空积木表也不查 for 自己）", () -> {
            Path d = tmpDir();
            write(d.resolve("ok_for.ac"), """
                    {"name":"ok_for","steps":[
                      {"id":"loop","block":"for","params":{"list":"$input.items","as":"m"},
                       "children":[{"id":"x","block":"set","params":{"v":"$var.m"}}]}
                    ]}
                    """);
            AcxLoader.LoadReport rep = AcxLoader.loadAll(d, new Fake.Registry(), false, true);
            eq(1, rep.registered().size(), "应注册: " + rep.errors());
        });
    }

/** 33 组：AC-B12 控制块参数快照跨断点（for 续跑不丢列表 + 游标接着走） */
    private static void ctrlSnapshot() {
        T.group("33 控制块快照：for 跨断点续跑（列表快照 + 迭代游标）");

        List<Object> matches = List.of(
                Fake.params("x", 1, "block", "minecraft:stone"),
                Fake.params("x", 2, "block", "minecraft:dirt"),
                Fake.params("x", 3, "block", "minecraft:coal_ore"));

        // TODO(AC-B12 余项)：「for 之前的步骤暂停 → 续跑后 for 拿不到 $scan.matches」尚未定位。
        // 现象：首跑在第 0 步 PAUSED（断点=0，续跑会重跑它），续跑仍报
        // 「each (for) 说: list 必须指向一个列表，实际是: $scan.matches」。
        // 已排除：断点位置正确(0)、params 缓存与 _outs 回退都在、for 元素绑定真机可用。
        // 待查：续跑时 for 绑定用的 allOutputs 是否真的含 scan（怀疑 runFrame 在 resume 时
        // 用的是另一份 map，或 scan 的成功输出没进 allOutputs）。先不用假绿测试盖住它。

        T.test("for 循环中途暂停 → resume 从游标接着走（不重跑已处理元素）", () -> {
            Fake.resetCalls();
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", matches)))
                    .add(Fake.fixed("mine", Fake.params("mined", 1, "count", 1)))
                    .add(Fake.pauserOnce("mark", new Fake.PauseOnce(), "mark 让位一次"));
            AcxDefinition d = def(
                    step("scan", "scan_blocks", Fake.params("block_ids", "minecraft:stone", "radius", 8)),
                    ctrl("each", "for", Fake.params("list", "$scan.matches", "as", "m"),
                            step("dig", "mine", Fake.params("block_ids", "$var.m.block", "count", 1)),
                            step("mark", "mark", Fake.params("round", "$loop.iter"))));
            AcxRunner r = AcxRunner.builder().tools(reg).build();
            AcxRunRecord first = r.run(d, Map.of());
            eq(AcxStatus.PAUSED, first.status(), "第 1 轮 mark 让位");
            int afterFirst = Fake.calls.count("mine");
            T.isTrue(afterFirst >= 1, "暂停前至少挖了一次: " + afterFirst);
            AcxRunRecord second = r.resume(d, first, Map.of());
            eq(AcxStatus.SUCCESS, second.status(), "续跑到完成（err=" + second.errorMessage() + "）");
            eq(3, Fake.calls.count("mine"), "★ 总共正好 3 次（游标避免了重跑）");
        });

        T.test("快照限量 64 条（记录不膨胀）", () -> {
            Fake.resetCalls();
            List<Object> big = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                big.add(Fake.params("x", i, "block", "minecraft:stone"));
            }
            Fake.Registry reg = new Fake.Registry()
                    .add(Fake.fixed("scan_blocks", Fake.params("matches", big)))
                    .add(Fake.fixed("mine", Fake.params("mined", 1, "count", 1)));
            AcxDefinition d = def(
                    step("scan", "scan_blocks", Fake.params("block_ids", "minecraft:stone", "radius", 8)),
                    ctrl("each", "for", Fake.params("list", "$scan.matches", "as", "m", "max_iters", 1),
                            step("dig", "mine", Fake.params("block_ids", "$var.m.block", "count", 1))));
            AcxRunRecord rec = AcxRunner.builder().tools(reg).build().run(d, Map.of());
            Object snap = rec.vars().get("_params_each");
            T.notNull(snap, "快照应落在 vars 里");
            T.isTrue(snap instanceof Map, "快照是 Map");
            Object list = ((Map<?, ?>) snap).get("list");
            T.isTrue(list instanceof List<?>, "快照含 list");
            eq(64, ((List<?>) list).size(), "★ 只存前 64 条");
        });
    }
}
