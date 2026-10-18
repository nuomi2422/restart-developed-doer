package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10 · RL-4 预算上限 —— {@code ResourceBudget.REQUIRED} 的<b>值</b>钉死。
 *
 * <h2>为什么 {@code ResourceBudgetTest} 不算</h2>
 *
 * <p>同目录已有的 {@code ResourceBudgetTest} 只测<b>行为</b>：下界需要什么、缺口怎么渲染、
 * 齐了算不算达标。它用的可用库存是<b>照着当前值凑的</b>
 * （{@code chestplate 2 / fire_resistance_potion 3 / torch 16 / cooked_beef 16}）。
 * 于是把 {@code REQUIRED} 里的数字改掉，它照样全绿 ——
 * <b>测试会跟着被改坏的实现一起漂移</b>，这正是 {@code 61} §2.3 判 RL-4「🔴 裸」的原因。
 *
 * <p>本类只做一件事：把四个风险级别的<b>整张表</b>逐键逐值写成断言（用 {@code Map.equals}，
 * 它与迭代顺序无关，所以能抗 {@code Map.of} 的随机顺序）。
 * 改动只有一个入口：先改这里，再改 {@code ResourceBudget.REQUIRED}。
 *
 * <h2>RL-4 的失败形态（清单 {@code 30} 第 40 行）</h2>
 * 「超额未拦」—— 把门槛调小，AI 就会在储备不足时踏进下界/末地；
 * 反过来调大，则永远「缺这个」而寸步难行。两个方向都坏，且都不会自己报错。
 *
 * <p><b>零生产改动、零构建改动、零 Minecraft。</b>
 */
class ResourceBudgetPinTest {

    /** 与 {@code ResourceBudget.REQUIRED} 当前内容逐键逐值一致（2026-10-03 现测）。 */
    private static final Map<String, Integer> MINING = Map.of(
            "minecraft:torch", 16,
            "minecraft:bread", 4);

    private static final Map<String, Integer> NETHER = Map.of(
            "minecraft:diamond_helmet", 2,
            "minecraft:diamond_chestplate", 2,
            "minecraft:diamond_leggings", 2,
            "minecraft:diamond_boots", 2,
            "minecraft:fire_resistance_potion", 3,
            "minecraft:torch", 16,
            "minecraft:cooked_beef", 16);

    private static final Map<String, Integer> END = Map.of(
            "minecraft:diamond_chestplate", 1,
            "minecraft:bow", 1,
            "minecraft:arrow", 32,
            "minecraft:ender_pearl", 12,
            "minecraft:water_bucket", 1,
            "minecraft:cooked_beef", 16,
            "minecraft:golden_apple", 2);

    // ═══════════════════════════════════════════════════════════════
    //  形状：级别全集 + 每级都有表
    // ═══════════════════════════════════════════════════════════════

    @Test
    void riskLevelsAreExactlyTheseFour() {
        assertEquals(List.of(RiskLevel.NORMAL, RiskLevel.MINING, RiskLevel.NETHER, RiskLevel.END),
                List.of(RiskLevel.values()),
                "★ RiskLevel 加了新档位却没在 REQUIRED 里给预算 —— "
                        + "requiredFor 会静默返回空表（getOrDefault），该档位从此「零门槛」。"
                        + "要加档位：先在本类补断言，再去 ResourceBudget.REQUIRED 补表。");
    }

    @Test
    void everyRiskLevelHasAStableNonNullTable() {
        for (RiskLevel lv : RiskLevel.values()) {
            Map<String, Integer> need = ResourceBudget.requiredFor(lv);
            assertNotNull(need, lv + " 的预算表不许为 null");
            // 连续两次取必须是同一份内容：REQUIRED 是静态不可变表，不该有任何运行期漂移。
            assertEquals(need, ResourceBudget.requiredFor(lv), lv + " 的预算表两次取不一致");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  值：逐级整表钉死
    // ═══════════════════════════════════════════════════════════════

    @Test
    void normalBudgetStaysEmpty() {
        assertEquals(Map.of(), ResourceBudget.requiredFor(RiskLevel.NORMAL),
                "★ 主世界常规不该有储备门槛。给它加门槛 =  village/近处采集平白被拦。");
    }

    @Test
    void miningBudgetIsExactlyTorch16Bread4() {
        assertEquals(MINING, ResourceBudget.requiredFor(RiskLevel.MINING),
                "★ 下矿/洞穴/夜行的最低储备被改了。torch 16 = 够铺一段路；bread 4 = 够熬一次夜。"
                        + "要改先改本断言。（原值：" + ResourceBudget.requiredFor(RiskLevel.MINING) + "）");
    }

    @Test
    void netherBudgetIsExactlyTwoSetsPlusThreePotions() {
        assertEquals(NETHER, ResourceBudget.requiredFor(RiskLevel.NETHER),
                "★ 下界预算被改了。四件甲各 2 = 一套穿、一套备用（掉进岩浆/lava 时那才是救命的一整套）；"
                        + "抗火药水 3；干粮 16。改小 = 裸装进下界；改大 = 永远「缺甲」下不去。"
                        + "（原值：" + ResourceBudget.requiredFor(RiskLevel.NETHER) + "）");
    }

    @Test
    void endBudgetIsExactlyBowArrowPearlBucketGoldenApple() {
        assertEquals(END, ResourceBudget.requiredFor(RiskLevel.END),
                "★ 末地预算被改了。箭 32 / 珍珠 12 是「进末地还能打回来」的底线；"
                        + "水桶 1 与金苹果 2 分别是落地保命与摔伤兜底。（原值："
                        + ResourceBudget.requiredFor(RiskLevel.END) + "）");
    }

    // ═══════════════════════════════════════════════════════════════
    //  形状纪律：命名空间 / 正数 / 不可变 / 边界严格小于
    // ═══════════════════════════════════════════════════════════════

    @Test
    void everyBudgetKeyCarriesTheMinecraftNamespace() {
        for (RiskLevel lv : RiskLevel.values()) {
            for (String key : ResourceBudget.requiredFor(lv).keySet()) {
                assertTrue(key.startsWith("minecraft:"),
                        "★ " + lv + " 的预算键 '" + key + "' 少了 minecraft: 命名空间 —— "
                                + "mod 兼容靠的就是 unknown 键容忍，写成裸名会让这项永远算「缺」。"
                                + "同族纪律见 InventoryGroupsPinTest。");
            }
        }
    }

    @Test
    void everyBudgetValueIsPositive() {
        for (RiskLevel lv : RiskLevel.values()) {
            for (Map.Entry<String, Integer> e : ResourceBudget.requiredFor(lv).entrySet()) {
                assertTrue(e.getValue() != null && e.getValue() > 0,
                        "★ " + lv + " 的 '" + e.getKey() + "' 门槛是 " + e.getValue()
                                + " —— 0 或负数等于「有这一项但永远不拦」，是伪装成门槛的假门槛。");
            }
        }
    }

    @Test
    void budgetTablesAreImmutable() {
        Map<String, Integer> need = ResourceBudget.requiredFor(RiskLevel.NETHER);
        assertThrows(UnsupportedOperationException.class, () -> need.put("minecraft:obsidian", 999),
                "★ 预算表必须不可变：调用方就地改它 = 下一个同伴按被改过的门槛判断（跨同伴污染）。");
        assertEquals(NETHER, ResourceBudget.requiredFor(RiskLevel.NETHER),
                "★ 预算表被运行期改动了 —— REQUIRED 是静态底座，任何漂移都是 bug。");
    }

    @Test
    void thresholdIsStrictlyLessThanSoExactlyEnoughPasses() {
        // 差一个也该拦（严格小于），刚好够就该放行 —— 少算一格会让「刚好备齐」的正常计划被永久拦死。
        assertTrue(ResourceBudget.missingFor(RiskLevel.NETHER,
                        Map.of("minecraft:diamond_helmet", 1)).stream()
                        .anyMatch(s -> s.contains("diamond_helmet") && s.contains("need 2")),
                "差 1 件就必须报缺口");
        Map<String, Integer> exact = ResourceBudget.requiredFor(RiskLevel.NETHER);
        assertTrue(ResourceBudget.satisfied(RiskLevel.NETHER, exact),
                "★ 恰好等于门槛时必须算达标（判据是 have < need，不是 have <= need）。"
                        + "改成 <= 会把「刚好备齐」全判成缺，AI 会无限囤积。");
        assertTrue(ResourceBudget.missingFor(RiskLevel.NETHER, null).size() == NETHER.size(),
                "库存读不到（null）时必须报<b>全部</b>缺口，而不是当作达标放行"
                        + "——「读不到」与「没有」在判据里不许混同（同族坑：0 有三种含义）。");
    }
}
