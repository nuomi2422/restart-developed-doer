package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.ItemSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 携带器「真短路判断链」的契约测试（{@code 38号v3.5 §1}）。
 *
 * <p><b>为什么必须能证明短路</b>：原实现是<b>把三级无条件全算一遍</b> ——
 * 那是扁平打分，不是判断链。但「为什么短路了」不能只靠读 why 文本自证，
 * 所以 {@link CarrierChain.Facts} 带<b>求值日志</b>，本类用<b>计数器</b>断言
 * 「后续级<b>确实没被调用</b>」。
 */
class CarrierChainTest {

    /**
     * ★ 进程级生效链必须每个用例刷回 DEFAULT。
     *
     * <p>不刷的后果（2026-10-06 实测）：别的测试类批准过的规则留在静态
     * {@code OVERRIDE} 里，本类「默认链的整体行为」那几条会读到它 ——
     * 短路位置整体前移，{@code why()} 里冒出陌生规则名、{@code carryList()} 变空。
     */
    @BeforeEach
    void isolateCarrierStore() {
        CarrierStoreIsolation.installEmpty();
    }

    /** 直接跑链并把 facts 的日志带出来。 */
    private record Run(CarrierChain.Result r, CarrierChain.Facts facts) {}

    private static Run run(String snapshot, List<CarrierChain.Rule> rules) {
        Memo m = new Memo("m", "p", "s", "t", snapshot, 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        // 重新跑一次以便拿到 facts（assessCarrier 内部已跑过，这里只为读日志）
        java.util.Map<String, String> kv = new java.util.LinkedHashMap<>();
        for (String part : snapshot.toLowerCase(java.util.Locale.ROOT).split("[,;]")) {
            String p = part.trim();
            int sep = -1;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c == '=' || c == ':') {
                    sep = i;
                    break;
                }
            }
            if (sep <= 0) {
                continue;
            }
            kv.putIfAbsent(p.substring(0, sep).replaceAll("[^A-Za-z0-9_]", ""),
                    p.substring(sep + 1).replaceAll("[^A-Za-z0-9_./-]", ""));
        }
        CarrierChain.Facts f = CarrierChain.factsOf(kv, snapshot.toLowerCase(java.util.Locale.ROOT));
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        return new Run(r, f);
    }

    // ---------- C1 短路真的发生 ----------

    @Test
    void whyReportsTheRealNumberOfUnEvaluatedRules() {
        // 2026-10-01 实机抓到：原来用 skippedLevels().size()，而它每次短路只塞一条
        // → 4 条规则在第 1 级短路时报「后续 1 项未求值」，**实际是 3 项**。
        // 报不对的数 = 让「没做的事」看起来像只做了一件 → B21 同一类。
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("一", f -> false, List.of(), List.of("缺一")),
                new CarrierChain.Rule("二", f -> true, List.of()),
                new CarrierChain.Rule("三", f -> true, List.of()),
                new CarrierChain.Rule("四", f -> true, List.of()));
        CarrierChain.Facts f = CarrierChain.factsOf(java.util.Map.of(), "");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        assertEquals(0, r.stoppedAt());
        assertTrue(r.why().contains("后续 3 项未求值"), "4 条规则第 1 级短路 = 后续 3 项：" + r.why());
    }

    @Test
    void whySaysAllRulesEvaluatedWhenNoneShortCircuits() {
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("一", f -> true, List.of()),
                new CarrierChain.Rule("二", f -> true, List.of()));
        CarrierChain.Facts f = CarrierChain.factsOf(java.util.Map.of(), "");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        assertTrue(r.why().contains("全部 2 级已求值"), r.why());
    }

    @Test
    void laterRuleIsNotInvokedWhenEarlierOneFails() {
        List<String> invoked = new ArrayList<>();
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("第一级", f -> {
                    invoked.add("第一级");
                    return false;
                }, List.of("a"), List.of("补齐第一级")),
                new CarrierChain.Rule("第二级", f -> {
                    invoked.add("第二级");
                    return true;
                }, List.of("b")),
                new CarrierChain.Rule("第三级", f -> {
                    invoked.add("第三级");
                    return true;
                }, List.of("c")));

        CarrierChain.Facts f = CarrierChain.factsOf(
                java.util.Map.of("hp", "20"), "hp=20");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);

        assertEquals(List.of("第一级"), invoked, "★ 后续级的判定函数**根本不该被调用**");
        assertTrue(r.shortCircuited());
        assertEquals(0, r.stoppedAt());
        assertEquals(List.of("第一级"), r.evaluatedLevels());
        assertTrue(r.skippedLevels().size() > 0);
    }

    @Test
    void whySaysLaterLevelsWereNotEvaluated() {
        // B21 同一类：让「没做的事」看起来像做过 = 同一种错
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("闸一", f -> false, List.of(), List.of("补齐闸一")),
                new CarrierChain.Rule("闸二", f -> true, List.of()));
        CarrierChain.Facts f = CarrierChain.factsOf(java.util.Map.of(), "");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        assertTrue(r.why().contains("未求值"), r.why());
        assertTrue(r.why().contains("短路"), r.why());
    }

    @Test
    void failedLevelStillCarriesWhatIsMissing() {
        // ⚠️ 短路后若什么都不带，调用方只知道「不行」却不知道「缺什么」→ 那是功能回退。
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("闸一", f -> false, List.of("不该带的"), List.of("缺的是这个")));
        CarrierChain.Facts f = CarrierChain.factsOf(java.util.Map.of(), "");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        assertEquals(List.of("缺的是这个"), r.carry());
        assertFalse(r.carry().contains("不该带的"), "成立的才带 carry，不成立的带 fix");
    }

    @Test
    void allRulesPassingMeansNoShortCircuit() {
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("一", f -> true, List.of("x")),
                new CarrierChain.Rule("二", f -> true, List.of("y")));
        CarrierChain.Facts f = CarrierChain.factsOf(java.util.Map.of(), "");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        assertFalse(r.shortCircuited());
        assertEquals(List.of("x", "y"), r.carry());
        assertTrue(r.why().contains("全部 2 级已求值"), "未短路时要说清**几级都求值了**：" + r.why());
    }

    @Test
    void predicateThrowingIsTreatedAsNotHoldingAndStops() {
        // 判定本身出错 → 视为不成立并短路，**不猜**
        List<CarrierChain.Rule> rules = List.of(
                new CarrierChain.Rule("会炸的", f -> {
                    throw new IllegalStateException("boom");
                }, List.of(), List.of("判定出错时的兜底")),
                new CarrierChain.Rule("后面的", f -> true, List.of("不该到这")));
        CarrierChain.Facts f = CarrierChain.factsOf(java.util.Map.of(), "");
        CarrierChain.Result r = CarrierChain.evaluate(rules, f);
        assertTrue(r.shortCircuited());
        assertTrue(r.why().contains("判定出错"), r.why());
        assertFalse(r.carry().contains("不该到这"));
    }

    // ---------- C2/C3/C4 装备语义（不是「非空」）----------

    @Test
    void slabIsNotAWeapon() {
        // 实机抓到：主手拿的是某模组的台阶方块，被 truthy 判成「有武器」
        assertFalse(ItemSemantics.isWeapon("smart_slab_init"));
        assertFalse(ItemSemantics.isWeapon("stone"));
        assertFalse(ItemSemantics.isWeapon("dirt"));
        assertFalse(ItemSemantics.isWeapon("bread"));
    }

    @Test
    void realWeaponsAreRecognised() {
        assertTrue(ItemSemantics.isWeapon("iron_sword"));
        assertTrue(ItemSemantics.isWeapon("diamond_axe"));
        assertTrue(ItemSemantics.isWeapon("bow"));
        assertTrue(ItemSemantics.isWeapon("crossbow"));
        assertTrue(ItemSemantics.isWeapon("trident"));
        assertTrue(ItemSemantics.isWeapon("mace"));
    }

    @Test
    void dirtIsNotArmor() {
        assertFalse(ItemSemantics.isAnyArmor("dirt"));
        assertFalse(ItemSemantics.isAnyArmor("stone"));
        assertFalse(ItemSemantics.isAnyArmor("smart_slab_init"));
    }

    @Test
    void realArmorIsRecognised() {
        assertTrue(ItemSemantics.isAnyArmor("iron_chestplate"));
        assertTrue(ItemSemantics.isAnyArmor("leather_helmet"));
        assertTrue(ItemSemantics.isAnyArmor("golden_leggings"));
        assertTrue(ItemSemantics.isAnyArmor("diamond_boots"));
    }

    @Test
    void negativeTokensNeverCountAsPresent() {
        for (String neg : List.of("none", "no", "false", "null", "无", "")) {
            assertFalse(ItemSemantics.isWeapon(neg), "否定词被判成有武器：" + neg);
            assertFalse(ItemSemantics.isAnyArmor(neg), "否定词被判成有护甲：" + neg);
        }
    }

    // ---------- C6 可插拔 ----------

    @Test
    void addingARuleDoesNotChangeExistingRulesJudgement() {
        // 可插拔：在链尾加一条，既有的判定结果不变
        String snap = "hp=6/20, armor=iron_chestplate, weapon=iron_sword, nearby=zombie";
        List<CarrierChain.Rule> base = List.of(
                new CarrierChain.Rule("指向谁", f -> f.hostileNearby(), List.of("战斗经验")),
                new CarrierChain.Rule("血量", f -> !"CRITICAL".equals(f.hpBand()), List.of("食物")));
        List<CarrierChain.Rule> extended = new ArrayList<>(base);
        extended.add(new CarrierChain.Rule("新增", f -> true, List.of("新增携带")));

        Run a = run(snap, base);
        Run b = run(snap, extended);
        assertEquals(a.r().evaluatedLevels(), b.r().evaluatedLevels().subList(0, a.r().evaluatedLevels().size()),
                "加规则不该改动既有级是否被求值");
        assertTrue(b.r().carry().containsAll(a.r().carry()), "加规则不该改动既有级带出的内容");
        assertTrue(b.r().carry().contains("新增携带"));
    }

    // ---------- 默认链的整体行为 ----------

    @Test
    void defaultChainKeepsOldSummaryFormatAndAppendsTrace() {
        // 旧测试（2026-09-29 的回归护栏）断言 why 含「护甲=无」「血量=CRITICAL」等，
        // 所以 why = 旧摘要 + 新轨迹，两套断言同时成立
        Memo m = new Memo("m", "p", "s", "t", "hp=6/20, armor=none, nearby=zombie", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals("HOSTILE_NEARBY", a.target());
        assertTrue(a.why().contains("护甲=无"), a.why());
        assertTrue(a.why().contains("未求值"), a.why());
    }

    @Test
    void defaultChainWithSlabInHandStillAsksForRealWeapon() {
        Memo m = new Memo("m", "p", "s", "t",
                "hp=6/20, armor=iron_chestplate, weapon=smart_slab_init, nearby=zombie", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertTrue(a.why().contains("武器=无"), "拿着方块不该算有武器：" + a.why());
        assertTrue(a.carryList().contains("武器获取类工具"), a.carryList().toString());
    }

    @Test
    void defaultChainShortCircuitsWhenNoTargetCanBeIdentified() {
        // 2026-10-01 实机抓到：第 1 级原本把 hasHp() 也算成「目标明确」，
        // 于是只有 hp/food/dim/pos 的快照会让第 1 级成立 → 后面全判完，**短路没机会发生**。
        // 但 B5 第 1 级问的是「目标是谁」，血量答不了这个问题。
        Memo m = new Memo("m", "p", "s", "t", "hp=20/20, food=20, dim=overworld, pos=0,64,0", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertTrue(a.why().contains("未求值"), "第 1 级不成立就该短路：" + a.why());
        // ⚠️ 「缺什么」在 carryList 里（fix），不在 why 里 —— 断言要打对地方
        assertTrue(a.carryList().contains("先弄清这轮的目标是谁"),
                "失败的级要说出缺什么（携带清单里）: " + a.carryList());
    }

    @Test
    void defaultChainDoesNotShortCircuitWhenTargetIsKnown() {
        Memo m = new Memo("m", "p", "s", "t", "hp=20/20, armor=iron_chestplate, nearby=cow, dim=overworld", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertFalse(a.why().contains("未求值"), "有 nearby=cow → 第 1 级成立，不该短路：" + a.why());
    }

    @Test
    void blankSnapshotStillReturnsUnknownWithoutAskingAnything() {
        Memo m = new Memo("m", "p", "s", "t", "", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();
        assertEquals("UNKNOWN", a.target());
        assertEquals(-1, a.hp());
        assertTrue(a.carryList().isEmpty());
    }
}