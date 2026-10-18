package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T5 · 背包分组白名单（<b>改动前完全没有任何测试</b>）。
 *
 * <h2>为什么这份清单必须被钉住</h2>
 *
 * <p>{@link InventoryGroups} 是 {@code group=food|wood|blocks} 这三种判据的<b>唯一</b>实现，
 * 也就是规划器说「搞点吃的 / 搞点木头 / 搞点建材」时真正的判据来源。
 * 它是三个手写的硬编码白名单：
 *
 * <ul>
 *   <li>FOOD：32 项（熟食、生肉与腐肉、甜点、汤类）</li>
 *   <li>WOOD：8 个树种 × (log/wood/planks + stripped×2) = 40，加下界 2 种 × 5 = 10，
 *       加 bamboo_planks = <b>51 项</b></li>
 *   <li>BLOCKS：17 项，且额外通过「任何以 {@code _planks} 结尾且在 WOOD 里」的兜底吃下全部木板</li>
 * </ul>
 *
 * <p>改动前没有一行测试碰过它。而删掉或改动其中一项，后果是
 * <b>「条件建链能过 → 判定恒为假 → 整条链静默卡死且不产生失败」</b>——
 * 正是 {@code RddChainFactory.validateCondition} 注释里反复警告的那种事故形态
 * （RL-18 同族）。而且它<b>不会报任何错</b>，只会让 AI 一直做、做、做。
 *
 * <p>本类用「整组计数」钉住数量（而不是逐项列白名单，那样维护成本太高），
 * 再用「关键成员 + 常见误加项」钉住成员关系。
 *
 * <p>零生产改动、零构建改动、零 Minecraft（纯集合逻辑，不读背包）。
 */
class InventoryGroupsPinTest {

    /** 本仓当前的三种分组。要加第四种请同步本类 + RddDecomposer 提示词 + HardCodedEvaluator。 */
    private static final List<String> PINNED_GROUPS = List.of("food", "wood", "blocks");

    /** 当前白名单的规模（2026-10-02 起 FOOD 32 / WOOD 51 / BLOCKS 17）。 */
    private static final int FOOD_SIZE = 32;
    private static final int WOOD_SIZE = 51;
    private static final int BLOCKS_SIZE = 17;

    private static Map<String, Integer> inv(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    // ── ① 分组名清单 ───────────────────────────────────────────────

    @Test
    void knownGroupsArePinnedToExactlyThree() {
        for (String g : PINNED_GROUPS) {
            assertTrue(InventoryGroups.known(g), "'" + g + "' 必须是已知分组");
        }
        for (String notKnown : List.of("tools", "weapon", "armor", "ore", "mob_drop", "", "FOOD", "Food")) {
            assertFalse(InventoryGroups.known(notKnown),
                    "'" + notKnown + "' 不是合法分组。group 判据拼错时 HardCodedEvaluator 会直接判 false，"
                            + "所以这里必须是精确匹配（不接受大小写变体）");
        }
        assertFalse(InventoryGroups.known(null));
    }

    @Test
    void pinnedGroupListMatchesWhatKnownAccepts() {
        Set<String> accepted = new java.util.HashSet<>();
        for (String c : List.of("food", "wood", "blocks", "tools", "weapon", "armor", "ore", "item")) {
            if (InventoryGroups.known(c)) accepted.add(c);
        }
        assertEquals(Set.copyOf(PINNED_GROUPS), accepted, "★ 实现的分组集合与本类钉住的清单不一致");
    }

    // ── ② 三组白名单的规模（核心 pin）──────────────────────────────

    @Test
    void foodGroupHasExactly32Members() {
        // 32 项 = 熟食/甜点/汤类 24 + 生肉与腐肉 8。逐个列出来只为凑一次 count。
        Map<String, Integer> all = inv(
                "minecraft:bread", 1, "minecraft:carrot", 1, "minecraft:baked_potato", 1, "minecraft:beetroot", 1,
                "minecraft:apple", 1, "minecraft:golden_apple", 1, "minecraft:enchanted_golden_apple", 1,
                "minecraft:golden_carrot", 1, "minecraft:cooked_beef", 1, "minecraft:cooked_porkchop", 1,
                "minecraft:cooked_chicken", 1, "minecraft:cooked_mutton", 1, "minecraft:cooked_rabbit", 1,
                "minecraft:cooked_cod", 1, "minecraft:cooked_salmon", 1, "minecraft:melon_slice", 1,
                "minecraft:sweet_berries", 1, "minecraft:glow_berries", 1, "minecraft:pumpkin_pie", 1,
                "minecraft:mushroom_stew", 1, "minecraft:beetroot_soup", 1, "minecraft:rabbit_stew", 1,
                "minecraft:cookie", 1, "minecraft:dried_kelp", 1,
                "minecraft:beef", 1, "minecraft:porkchop", 1, "minecraft:chicken", 1, "minecraft:mutton", 1,
                "minecraft:rabbit", 1, "minecraft:cod", 1, "minecraft:salmon", 1, "minecraft:rotten_flesh", 1);
        assertEquals(FOOD_SIZE, InventoryGroups.count("food", all),
                "★ food 组规模契约是 " + FOOD_SIZE + " 项。少一项 = 那个食物永远判不到"
                        + "（建链能过、判定恒假、静默卡死）；多一项 = 白名单被悄悄放宽。");
    }

    @Test
    void rawMeatCountsAsFoodSoTheAiCanTellWhyItPassed() {
        // 2026-10-02 修的真 bug：白名单里一个生肉都没有，AI 捡了生牛肉 → 组计数纹丝不动
        // → 判定恒假 → AI 完全不知道为什么。捡回来就该算数。
        for (String raw : List.of("beef", "porkchop", "chicken", "mutton", "rabbit",
                "cod", "salmon", "rotten_flesh")) {
            assertTrue(InventoryGroups.contains("food", "minecraft:" + raw),
                    raw + " 是生肉，捡回来确实推进续航目标，必须算数");
            assertEquals(1L, InventoryGroups.count("food", inv("minecraft:" + raw, 1)),
                    raw + " 单独 1 件必须让 food 计数动起来");
        }
    }

    @Test
    void woodGroupHasExactly51Members() {
        Map<String, Integer> all = new LinkedHashMap<>();
        for (String species : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            for (String suffix : List.of("_log", "_wood", "_planks", "_log_stripped", "_wood_stripped")) {
                // stripped 的形状是 stripped_<species>_log，这里换一种拼法
                String id = suffix.endsWith("_stripped")
                        ? "minecraft:stripped_" + species + suffix.substring(0, suffix.length() - "_stripped".length())
                        : "minecraft:" + species + suffix;
                all.put(id, 1);
            }
        }
        for (String species : List.of("crimson", "warped")) {
            for (String suffix : List.of("_stem", "_hyphae", "_planks")) all.put("minecraft:" + species + suffix, 1);
            all.put("minecraft:stripped_" + species + "_stem", 1);
            all.put("minecraft:stripped_" + species + "_hyphae", 1);
        }
        all.put("minecraft:bamboo_planks", 1);
        assertEquals(8L * 5L + 2L * 5L + 1L, all.size(), "钉住前自检：构造出的背包应有 51 项");
        assertEquals(WOOD_SIZE, InventoryGroups.count("wood", all),
                "★ wood 组规模契约是 " + WOOD_SIZE + " 项（8 树种 × 5 + 下界 2 种 × 5 + 竹木板）。");
    }

    @Test
    void blocksGroupHasExactly17OwnMembers() {
        Map<String, Integer> own = inv(
                "minecraft:cobblestone", 1, "minecraft:cobbled_deepslate", 1, "minecraft:stone", 1,
                "minecraft:deepslate", 1, "minecraft:dirt", 1, "minecraft:coarse_dirt", 1,
                "minecraft:netherrack", 1, "minecraft:end_stone", 1, "minecraft:andesite", 1,
                "minecraft:diorite", 1, "minecraft:granite", 1, "minecraft:tuff", 1,
                "minecraft:blackstone", 1, "minecraft:basalt", 1, "minecraft:sandstone", 1,
                "minecraft:red_sandstone", 1, "minecraft:moss_block", 1);
        assertEquals(BLOCKS_SIZE, InventoryGroups.count("blocks", own),
                "★ blocks 组自有成员契约是 " + BLOCKS_SIZE + " 项（木板走兜底规则，不计入本项）");
    }

    // ── ③ blocks 的「木板兜底」规则 ─────────────────────────────────

    @Test
    void blocksGroupAbsorbsWoodThroughThePlanksSuffixRule() {
        // 这是 blocks 组唯一一条「越界」规则：任何以 _planks 结尾且在 WOOD 里的也算建材
        assertTrue(InventoryGroups.contains("blocks", "minecraft:oak_planks"), "木板必须同时算建材");
        assertTrue(InventoryGroups.contains("blocks", "minecraft:bamboo_planks"), "竹木板也算建材");
        assertTrue(InventoryGroups.contains("blocks", "minecraft:crimson_planks"));
        assertTrue(InventoryGroups.contains("blocks", "minecraft:warped_planks"));
        // 但原木 / 去皮原木不算建材 —— 这是个容易被人「顺手统一」掉的边界
        assertFalse(InventoryGroups.contains("blocks", "minecraft:oak_log"), "原木不是建材");
        assertFalse(InventoryGroups.contains("blocks", "minecraft:stripped_oak_log"));
        assertFalse(InventoryGroups.contains("blocks", "minecraft:oak_wood"), "去皮木不是建材");
    }

    @Test
    void blocksGroupDoesNotAbsorbArbitraryPlanksLookingItems() {
        // 以 _planks 结尾但不在 WOOD 里的 → 不算建材（否则「暗木告示牌」之类会被算进去）
        assertFalse(InventoryGroups.contains("blocks", "minecraft:planks"), "裸 planks 不是合法资源 id");
        assertFalse(InventoryGroups.contains("blocks", "minecraft:other_planks"));
    }

    // ── ④ 成员资格的具体边界（防「顺手放宽」）──────────────────────

    @Test
    void foodGroupKeepsSurvivalStaplesAndRejectsObviousNonFood() {
        for (String staple : List.of("bread", "carrot", "baked_potato", "beetroot", "apple",
                "cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_rabbit",
                "cooked_cod", "cooked_salmon", "melon_slice", "sweet_berries", "glow_berries",
                "pumpkin_pie", "mushroom_stew", "beetroot_soup", "rabbit_stew", "cookie",
                "dried_kelp", "golden_apple", "enchanted_golden_apple", "golden_carrot")) {
            assertTrue(InventoryGroups.contains("food", "minecraft:" + staple),
                    staple + " 是生存必需品，必须在 food 组里");
        }
        for (String notFood : List.of("wheat", "spider_eye", "raw_chickenfish",
                "poisonous_potato", "dirt", "oak_log", "stone")) {
            assertFalse(InventoryGroups.contains("food", "minecraft:" + notFood),
                    notFood + " 不在 food 白名单里（故意如此：它要么需要加工，要么是废料）。"
                            + "要加请先问：加了它「搞点吃的」就会拿它凑数");
        }
    }

    @Test
    void seedAndPlantStuffsStayOutEvenThoughTheyGrowIntoFood() {
        // 种下去会长成食物，但背包里的是种子/作物本身 —— 计进去会让「种田」变成「收种子」
        for (String notFood : List.of("wheat_seeds", "potato", "beetroot_seeds",
                "melon_seeds", "pumpkin_seeds", "sugar_cane", "nether_wart", "kelp", "wheat")) {
            assertFalse(InventoryGroups.contains("food", "minecraft:" + notFood),
                    notFood + " 虽能种出食物，但背包里这份不是食物本身");
        }
    }

    @Test
    void woodGroupCoversEverySpeciesAndItsStrippedForm() {
        for (String species : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            assertTrue(InventoryGroups.contains("wood", "minecraft:" + species + "_log"), species + " 原木");
            assertTrue(InventoryGroups.contains("wood", "minecraft:" + species + "_wood"), species + " 去皮原木");
            assertTrue(InventoryGroups.contains("wood", "minecraft:" + species + "_planks"), species + " 木板");
            assertTrue(InventoryGroups.contains("wood", "minecraft:stripped_" + species + "_log"),
                    species + " 去皮原木（stripped 前缀形态）");
            assertTrue(InventoryGroups.contains("wood", "minecraft:stripped_" + species + "_wood"),
                    species + " 去皮木（stripped 前缀形态）");
        }
        for (String species : List.of("crimson", "warped")) {
            assertTrue(InventoryGroups.contains("wood", "minecraft:" + species + "_stem"), species + " 菌柄");
            assertTrue(InventoryGroups.contains("wood", "minecraft:" + species + "_hyphae"), species + " 菌核");
            assertTrue(InventoryGroups.contains("wood", "minecraft:" + species + "_planks"), species + " 木板");
            assertTrue(InventoryGroups.contains("wood", "minecraft:stripped_" + species + "_stem"));
            assertTrue(InventoryGroups.contains("wood", "minecraft:stripped_" + species + "_hyphae"));
        }
    }

    @Test
    void woodGroupRejectsNonWoodPlants() {
        for (String notWood : List.of("bamboo", "vine", "leaves", "oak_sapling", "cactus",
                "sweet_berries", "short_grass", "bamboo_block")) {
            assertFalse(InventoryGroups.contains("wood", "minecraft:" + notWood),
                    notWood + " 不算木材。注意 bamboo_planks 算、bamboo 不算 —— 这是刻意边界");
        }
    }

    @Test
    void containsRequiresTheMinecraftNamespace() {
        assertFalse(InventoryGroups.contains("food", "bread"), "裸 id 必须被拒（资源 id 形状校验）");
        assertFalse(InventoryGroups.contains("food", ""), "空 id");
        assertFalse(InventoryGroups.contains("food", null), "null id");
        assertFalse(InventoryGroups.contains("food", "MODPREFIX:bread"), "非 minecraft 命名空间必须被拒");
        assertTrue(InventoryGroups.contains("food", "minecraft:bread"), "唯一合法前缀是 minecraft:");
    }

    @Test
    void containsWithUnknownGroupIsAlwaysFalse() {
        assertFalse(InventoryGroups.contains("tools", "minecraft:diamond_pickaxe"));
        assertFalse(InventoryGroups.contains("", "minecraft:stone"));
    }

    // ── ⑤ count 的语义 ─────────────────────────────────────────────

    @Test
    void countIgnoresNonPositiveAndNullValues() {
        assertEquals(3L, InventoryGroups.count("food", inv(
                "minecraft:bread", 2, "minecraft:carrot", 1, "minecraft:cooked_beef", 0)));
        assertEquals(2L, InventoryGroups.count("food", inv(
                "minecraft:bread", 2, "minecraft:cooked_beef", 0)), "0 计数直接被跳过，不加进总和（所以是 2 不是 3）");
        Map<String, Integer> withNull = inv("minecraft:bread", 2);
        withNull.put("minecraft:carrot", null);
        assertEquals(2L, InventoryGroups.count("food", withNull), "null 计数不许抛异常（背包快照可能缺项）");
        assertEquals(0L, InventoryGroups.count("food", Map.of()), "空背包当然是 0");
    }

    @Test
    void countOnUnknownGroupOrNullInventoryIsZero() {
        assertEquals(0L, InventoryGroups.count("tools", inv("minecraft:stone", 99)));
        assertEquals(0L, InventoryGroups.count(null, inv("minecraft:bread", 99)));
        assertEquals(0L, InventoryGroups.count("food", null));
    }

    @Test
    void countOnlyCountsMembersOfTheRequestedGroup() {
        Map<String, Integer> mixed = inv(
                "minecraft:bread", 2,      // food
                "minecraft:oak_log", 3,   // wood
                "minecraft:stone", 5);    // blocks
        assertEquals(2L, InventoryGroups.count("food", mixed));
        assertEquals(3L, InventoryGroups.count("wood", mixed));
        assertEquals(5L, InventoryGroups.count("blocks", mixed));
    }

    @Test
    void countDoesNotOverflowOnLargeStacks() {
        // 与 HardCodedEvaluatorTest 里那条 4294967294L 的溢出断言同源：
        // 两个各 2^31-1 的堆叠相加 = 2^32-2。若计数误用 int，这里会溢出成 -2，
        // 于是「拥有 42 亿个面包」被判成「欠 42 亿个」。
        assertEquals(4_294_967_294L, InventoryGroups.count("food",
                        inv("minecraft:bread", 2_147_483_647, "minecraft:carrot", 2_147_483_647)),
                "★ 分组计数必须用 long 累加：int 会在 2^31 处溢出成负数，"
                        + "于是「拥有 42 亿个面包」被判成「欠 42 亿个」");
        assertEquals(2_147_483_647L, InventoryGroups.count("food", inv("minecraft:bread", 2_147_483_647)),
                "单个堆叠也必须是 long 结果（Integer.MAX_VALUE 不能被当成负数读回）");
    }
}