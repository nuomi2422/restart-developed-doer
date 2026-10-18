package com.dwinovo.numen.api.carrier;

import com.dwinovo.numen.api.carrier.CarrierChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code when} 表达式编译的<b>真值表</b>测试（2026-10-05 加固）。
 *
 * <p><b>为什么这批测试必须逐值验、不能只验「不抛错」</b>：修复前这段有两个
 * 「文档写着支持、代码其实不支持」的静默 bug ——
 * <ul>
 *   <li>{@code "="} 与 {@code "<="} 混在一次循环里 ⇒ {@code hp<=4} 的等号被当成相等号，
 *       {@code f.get("hp<=")} 恒空 ⇒ <b>条件恒假、规则永不生效</b>；</li>
 *   <li>没有 {@code ">"} 分支 ⇒ {@code hp>4} 退化成 {@code hp==4}。</li>
 * </ul>
 * 这两类都不抛异常，只在<b>真值</b>上错 —— 所以测试必须断言每个输入的每个取值。
 */
class CarrierWhenCompileTest {

    /**
     * 生效链是<b>进程级静态</b>（{@code CarrierRuleStore.OVERRIDE}）：
     * 先刷回「已安装、无已批准规则」再断言，否则 {@code effective()} 的结果
     * 取决于本类里别的用例（或别的测试类）跑没跑过。
     */
    @BeforeEach
    void isolateCarrierStore() {
        CarrierRuleStore.install(tmp("isolation"));
    }

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"), "when-" + n + "-" + System.nanoTime());
    }

    private static CarrierChain.Facts facts(String hp) {
        return CarrierChain.factsOf(Map.of("hp", hp), "hp=" + hp);
    }

    private static CarrierChain.Facts factsWith(String k, String v) {
        return CarrierChain.factsOf(Map.of(k, v), k + "=" + v);
    }

    // ── 数值比较：四个算子逐值验 ──────────────────────────────────────────────

    @Test
    void hpLessOrEqual_isRealComparison() {
        var p = CarrierRuleStore.compile("hp<=4");
        assertTrue(p.test(facts("4")), "hp=4 应满足 hp<=4");
        assertTrue(p.test(facts("3")), "hp=3 应满足 hp<=4");
        assertFalse(p.test(facts("5")), "hp=5 不该满足 hp<=4");
        assertFalse(p.test(facts("20")), "hp=20 不该满足 hp<=4");
    }

    @Test
    void hpGreaterThan_isNotEqual() {
        // ★ 修复前 hp>4 会落到 default → hp==4
        var p = CarrierRuleStore.compile("hp>4");
        assertTrue(p.test(facts("5")), "hp=5 应满足 hp>4");
        assertTrue(p.test(facts("20")), "hp=20 应满足 hp>4");
        assertFalse(p.test(facts("4")), "hp=4 不该满足 hp>4（修复前这里会返回 true）");
        assertFalse(p.test(facts("3")), "hp=3 不该满足 hp>4");
    }

    @Test
    void hpGreaterOrEqual_andLess() {
        assertTrue(CarrierRuleStore.compile("hp>=10").test(facts("10")));
        assertFalse(CarrierRuleStore.compile("hp>=10").test(facts("9")));
        assertTrue(CarrierRuleStore.compile("hp<10").test(facts("9")));
        assertFalse(CarrierRuleStore.compile("hp<10").test(facts("10")));
    }

    @Test
    void hpEqual_isExactMatch() {
        var p = CarrierRuleStore.compile("hp=4");
        assertTrue(p.test(facts("4")));
        assertFalse(p.test(facts("5")), "hp=4 是精确匹配，不该命中 5");
        assertFalse(p.test(facts("14")));
    }

    // ── 开关/装备类：修复前这些分支到不了 ─────────────────────────────────────

    /**
     * 有武器的快照事实。
     *
     * <p>★ 键名必须对得上真实实现：{@code Facts.hasRealWeapon()} 读的是
     * {@code weapon}/{@code sword}/{@code axe}/{@code bow}，且 {@code ItemSemantics.isWeapon}
     * 按<b>后缀</b>判（{@code *_sword} 等），不是按 {@code item_weapon=1}。
     * 测试夹具写错就会得到「恒假」的假结论 —— 那正是本文件要抓的那类错误。
     */
    private static CarrierChain.Facts factsWithSword() {
        return CarrierChain.factsOf(Map.of("weapon", "iron_sword"), "weapon=iron_sword");
    }

    @Test
    void hasEqualsWeapon_isNotSilentlyFalse() {
        // ★ 修复前 has=weapon 落到相等分支 → f.get("has") 恒空 ⇒ 恒假
        var p = CarrierRuleStore.compile("has=weapon");
        assertTrue(p.test(factsWithSword()),
                "拿着铁剑时 has=weapon 必须成立（修复前恒为假）");
        assertFalse(p.test(facts("20")),
                "空手时不该成立");
    }

    @Test
    void hasEqualsAndHasUnderscoreWeapon_agree() {
        var a = CarrierRuleStore.compile("has=weapon");
        var b = CarrierRuleStore.compile("has_weapon");
        for (CarrierChain.Facts f : List.of(factsWithSword(), facts("20"))) {
            assertTrue(a.test(f) == b.test(f),
                    "has=weapon 与 has_weapon 是同一个判断，结果必须一致");
        }
        assertTrue(b.test(factsWithSword()), "有剑时 has_weapon 必须成立");
    }

    @Test
    void hasEqualsArmor_readsRealArmor() {
        var p = CarrierRuleStore.compile("has=armor");
        assertTrue(p.test(CarrierChain.factsOf(Map.of("chestplate", "iron_chestplate"),
                "chestplate=iron_chestplate")), "穿铁胸甲时 has=armor 必须成立");
        assertFalse(p.test(facts("20")), "没护甲时不该成立");
    }

    @Test
    void hostileEqualsOne_readsTheSwitch() {
        var p = CarrierRuleStore.compile("hostile=1");
        boolean plain = p.test(CarrierChain.factsOf(Map.of(), "standing on stone"));
        boolean withZombie = p.test(CarrierChain.factsOf(Map.of(), "a zombie is nearby"));
        assertFalse(plain == withZombie,
                "hostile=1 必须真的读事实，而不是恒真或恒假（修复前它落到相等分支恒为假）");
    }

    @Test
    void bandEquals_isCaseInsensitive() {
        var p = CarrierRuleStore.compile("band=CRITICAL");
        assertTrue(p.test(facts("3")), "hp=3 是 CRITICAL 档");
        assertFalse(p.test(facts("20")), "hp=20 不是 CRITICAL 档");
    }

    @Test
    void commaIsAnd_notOr() {
        var p = CarrierRuleStore.compile("hp<=4,band=CRITICAL");
        assertTrue(p.test(facts("3")), "两个条件都满足");
        assertFalse(p.test(facts("5")), "hp=5 不满足第一个条件 ⇒ 整条为假（AND 不是 OR）");
    }

    // ── 拒绝错误输入：宁可报错，不要静默 ───────────────────────────────────────

    @Test
    void nonNumericComparison_reportsInsteadOfGuessing() {
        var e = assertThrows(IllegalArgumentException.class, () -> CarrierRuleStore.compile("hp<=abc"));
        assertTrue(e.getMessage().contains("整数"), "要说清是右边不是整数: " + e.getMessage());
    }

    @Test
    void numericCompareOnNonNumericKey_isRejectedExplicitly() {
        var e = assertThrows(IllegalArgumentException.class, () -> CarrierRuleStore.compile("level<=3"));
        assertTrue(e.getMessage().contains("hp"),
                "非数值键必须明说（要人工定），不许静默按字符串比: " + e.getMessage());
    }

    // ── food：饱食度（不是背包食物数）────────────────────────────────────

    private static CarrierChain.Facts foodFacts(String level) {
        return CarrierChain.factsOf(Map.of("food", level), "food=" + level);
    }

    @Test
    void foodComparisons_workAndAreDocumentedAsSatiation() {
        // food<=5 的语义是「快饿死了」，**不是**「没东西吃了」——
        // 快照里的 food 来自 PlayerFoodData.getFoodLevel()，与背包内容无关。
        var low = CarrierRuleStore.compile("food<=5");
        assertTrue(low.test(foodFacts("3")), "food=3 应满足 food<=5");
        assertFalse(low.test(foodFacts("12")), "food=12 不该满足");
        assertTrue(CarrierRuleStore.compile("food>=15").test(foodFacts("18")));
        assertTrue(CarrierRuleStore.compile("food<5").test(foodFacts("4")));
        assertFalse(CarrierRuleStore.compile("food<5").test(foodFacts("5")));
    }

    @Test
    void missingFood_isFalseNotZero() {
        // ★ 取不到 food 时必须「不成立」，不能当成 0 —— 0 是合法饱食度（饿到极限）
        var low = CarrierRuleStore.compile("food<=5");
        assertFalse(low.test(CarrierChain.factsOf(Map.of(), "nothing here")),
                "food 缺失时不能被当成 food=0（那样会误判成「快饿死了」）");
    }

    // ── food_items：背包里**可食用物品个数**（2026-10-06 补）─────────────────
    //
    // 用户实机要的是「没食物了要提醒」—— 而 food（饱食度）答不了这个问题：
    // 饱食度高也可能一颗都没有（刚吃完），饱食度低也可能塞满面包。
    // ★ 这个键的**生产端**（RddCarryHint / EnvSnapshot）在**没有数据时写 -1**，
    //   所以这里必须钉住「-1 / 缺失 ⇒ 不成立」，否则「读不到」会被当成「没有」。

    private static CarrierChain.Facts foodItemsFacts(String n) {
        return CarrierChain.factsOf(Map.of("food_items", n), "food_items=" + n);
    }

    @Test
    void foodItemsComparisons_work() {
        var none = CarrierRuleStore.compile("food_items<=0");
        assertTrue(none.test(foodItemsFacts("0")), "0 个食物是真值，必须触发");
        assertFalse(none.test(foodItemsFacts("3")), "3 个食物不该触发");
        assertFalse(none.test(foodItemsFacts("64")), "一整组食物不该触发");

        assertTrue(CarrierRuleStore.compile("food_items<5").test(foodItemsFacts("4")));
        assertFalse(CarrierRuleStore.compile("food_items<5").test(foodItemsFacts("5")));
        assertTrue(CarrierRuleStore.compile("food_items>=1").test(foodItemsFacts("1")));
    }

    @Test
    void unknownFoodItems_neverSatisfiesAnyComparison() {
        // ★ -1 是生产端写的「未知」哨兵（读世界失败），不是「一个都没有」。
        //   把它当成 0 会让规则在「其实有一背包食物」时也触发 —— DL-4 要禁的假事实。
        var none = CarrierRuleStore.compile("food_items<=0");
        assertFalse(none.test(foodItemsFacts("-1")), "未知（-1）不许满足 food_items<=0");
        assertFalse(none.test(CarrierChain.factsOf(Map.of(), "hp=20/20")),
                "键缺失时不许满足");
    }

    @Test
    void foodAndFoodItemsAreDifferentKeys_notSilentlyTheSame() {
        // 快照同时带两个键时，各读各的 —— 别让一个悄悄顶替另一个
        var f = CarrierChain.factsOf(Map.of("food", "20", "food_items", "0"),
                "food=20, food_items=0");
        assertTrue(CarrierRuleStore.compile("food_items<=0").test(f), "库存为 0 ⇒ 触发");
        assertFalse(CarrierRuleStore.compile("food<=5").test(f), "饱食度 20 ⇒ 不触发");
    }

    @Test
    void unknownToken_mentionsThatFoodIsSatiationNotInventory() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> CarrierRuleStore.compile("no_food_left"));
        assertTrue(e.getMessage().contains("饱食度"),
                "★ 报错要澄清 food 是饱食度，避免有人以为它代表背包食物: " + e.getMessage());
    }

    // ── 已批准规则的顺序 ──────────────────────────────────────────────────
    // 「已批准规则排在默认链之前」这条不变量由 learner 侧 CarrierApprovalFlowTest
    // 断言（它才能走完整 approve 写盘链路）。这里只锁纯函数部分：
    // 装上之后 effective() 至少要把已批准规则接进链里，且默认链仍在。

    @Test
    void effective_chain_containsDefaultsAndIsUsable() {
        Path dir = tmp("chain");
        CarrierRuleStore.install(dir);
        var chain = CarrierRuleStore.effective();
        assertTrue(chain.size() >= CarrierRules.DEFAULT.size(),
                "生效链至少要有内置默认规则");
        // 未装任何已批准规则时，链就是默认链本身
        assertEquals(CarrierRules.DEFAULT.size(), chain.size(),
                "没有已批准规则时不应凭空多出规则");
    }

    @Test
    void unknownToken_isRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> CarrierRuleStore.compile("moon_is_full"));
        assertTrue(e.getMessage().contains("moon_is_full"), "报错要点名那个不认识的词: " + e.getMessage());
    }

    @Test
    void badSwitchValue_isRejected() {
        // ★ 编译期就要拒：truthy 若写在 lambda 里，编译不报错、跑到求值才炸 ——
        //   而批准那一刻已经过去了（规则已进生效链），报错太晚。
        var e = assertThrows(IllegalArgumentException.class,
                () -> CarrierRuleStore.compile("hostile=maybe"));
        assertTrue(e.getMessage().contains("maybe"), "报错要点名那个非法值: " + e.getMessage());
    }

    // ── 与 hasItem 的具名写法一致（两种写法必须是同一个判断）──────────────────────

    @Test
    void lowHp_matchesHpLessOrEqual4() {
        var a = CarrierRuleStore.compile("low_hp");
        var b = CarrierRuleStore.compile("hp<=4");
        for (String hp : List.of("1", "3", "4", "5", "20")) {
            assertTrue(a.test(facts(hp)) == b.test(facts(hp)),
                    "low_hp 与 hp<=4 必须等价，hp=" + hp);
        }
    }
}