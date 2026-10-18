package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T4 · 世界事实判据的合法值清单（钉 schema）。
 *
 * <h2>为什么这个类只有 1 个测试却必须补</h2>
 *
 * <p>改动前 {@code WorldFactConditionsTest} 全仓只有<b>一个</b>测试方法
 * （{@code validatesExplicitEvidenceOnly}，8 个断言）。也就是说：<b>整个世界观只由 4 个
 * 字符串字面量决定，而它们没有任何独立的值钉死。</b>
 *
 * <h2>扩判据是「三处必须同步」的高危操作</h2>
 *
 * <pre>
 *   ① WorldFactConditions.valid / knownType   ← schema（这里）
 *   ② RddWorldFacts.matches                   ← 真身（Minecraft 侧读世界）
 *   ③ RddDecomposer SYSTEM_PROMPT + prompt     ← 告知规划器有哪些合法条件
 * </pre>
 *
 * <p>漏掉任何一处，后果都是 RL-18 同族事故：<b>规划器生成了一个永远判不出来的条件，
 * 整条链静默卡死且不产生失败</b>。更隐蔽的是
 * {@code RddDecomposer.parseOne} 现在对未知 type 是<b>静默丢弃整条二级、连报错都没有</b>——
 * 漏改 ① 时的表现是「规划器产出被悄悄吃掉」，看起来像模型不听话。
 *
 * <p>本类把「合法清单」变成显式的值，并附一条护栏：
 * 只要有人加了新 type，本类必须同步更新 —— 更新时会被迫去看另外两处。
 *
 * <p>零生产改动、零构建改动、零 Minecraft（纯 schema，不读世界）。
 */
class WorldFactConditionsPinTest {

    private static Map<String, Object> c(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── ① 合法 type 清单（核心 pin）─────────────────────────────────

    /** 本仓合法的世界事实类型全集（2026-10-02 从 4 扩到 7；2026-10-08 A 组再扩 4 个）。加新 type 时本清单必须同步改。 */
    private static final List<String> PINNED_TYPES = List.of(
            "advancement", "structure", "entity_killed", "base",
            "biome", "block_nearby", "container_nearby",
            "block_mined", "item_crafted", "item_used", "item_picked_up",
            "dimension", "position_at", "y_below",
            "entity_nearby", "block_at", "container_at");

    @Test
    void knownTypesArePinnedToTheFullSet() {
        for (String t : PINNED_TYPES) {
            assertTrue(WorldFactConditions.knownType(t), "'" + t + "' 必须是已知世界事实类型");
        }
        // 逐个核对「常见的误加项」都不在清单里
        for (String notKnown : List.of(
                "inventory",              // 背包类，走 HardCodedEvaluator，不走这里
                "item_held", "block_placed", "dimension_entered", "time_elapsed", "chat_said",
                "weather", "entity_seen", "recipe_known", "damage_taken",
                "Advancement", "BASE", "")) {
            assertFalse(WorldFactConditions.knownType(notKnown),
                    "'" + notKnown + "' 不在合法清单里。knownType 决定监测台的 evidenceSource 分流，"
                            + "多认一个 = 证据来源被错标（伪造成服务端世界事实）");
        }
        assertFalse(WorldFactConditions.knownType(null));
        assertFalse(WorldFactConditions.knownType(42));
    }

    @Test
    void unknownTypeIsRejectedLoudlyInSchema() {
        // 「合法但没实现」与「拼错了」在 schema 层都只能表现为 false —— 这正是危险之处：
        // parseOne 拿到 false 会静默丢弃整条二级。本类至少把「必须为 false」钉死，
        // 让想加新 type 的人一定会撞到这里。
        assertFalse(WorldFactConditions.valid(c("type", "item_held", "item", "minecraft:stone")),
                "未知 type 必须判非法（加了新 type 请同步本类 + RddWorldFacts.matches + RddDecomposer 提示词 + 本 pin，四处缺一不可）");
        assertFalse(WorldFactConditions.valid(c("type", "nearby_mob")));
        assertFalse(WorldFactConditions.valid(c("type", "chat_said")));
    }

    // ── ③ 2026-10-08 A 组（Stats）schema ──────────────────────────

    @Test
    void statsWorldFactTypesSchemaIsValid() {
        assertTrue(WorldFactConditions.valid(c("type", "block_mined", "block", "minecraft:stone", "minimum", 32)));
        assertTrue(WorldFactConditions.valid(c("type", "block_mined", "block", "#minecraft:ores")));
        assertTrue(WorldFactConditions.valid(c("type", "item_crafted", "item", "minecraft:bread", "minimum", 3)));
        assertTrue(WorldFactConditions.valid(c("type", "item_used", "item", "minecraft:bread")));
        assertTrue(WorldFactConditions.valid(c("type", "item_picked_up", "item", "#minecraft:planks")));
    }

    @Test
    void statsWorldFactTypesRejectMalformed() {
        assertFalse(WorldFactConditions.valid(c("type", "block_mined")), "缺 block");
        assertFalse(WorldFactConditions.valid(c("type", "block_mined", "block", "not_an_id")));
        assertFalse(WorldFactConditions.valid(c("type", "item_crafted")), "缺 item");
        assertFalse(WorldFactConditions.valid(c("type", "block_mined", "block", "minecraft:stone", "minimum", 0)), "minimum 必须 >0");
    }

    @Test
    void positionWorldFactTypesSchemaIsValid() {
        assertTrue(WorldFactConditions.valid(c("type", "dimension", "dimension", "minecraft:the_nether")));
        assertTrue(WorldFactConditions.valid(c("type", "y_below", "y", 0)));
        assertTrue(WorldFactConditions.valid(c("type", "y_below", "y", -59, "dimension", "minecraft:overworld")));
        assertTrue(WorldFactConditions.valid(c("type", "position_at", "x", 100, "y", 64, "z", 200)));
        assertTrue(WorldFactConditions.valid(c("type", "position_at", "x", 100, "y", 64, "z", 200, "radius", 8)));
    }

    @Test
    void positionWorldFactTypesRejectMalformed() {
        assertFalse(WorldFactConditions.valid(c("type", "dimension")), "缺 dimension");
        assertFalse(WorldFactConditions.valid(c("type", "y_below")), "缺 y");
        assertFalse(WorldFactConditions.valid(c("type", "y_below", "y", 1.5)), "y 必须整数");
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 1, "y", 2, "block", "minecraft:stone")), "缺 z");
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 1, "y", 2, "z", 3, "radius", 64)), "半径越界");
    }

    @Test
    void coordWorldFactTypesSchemaIsValid() {
        assertTrue(WorldFactConditions.valid(c("type", "entity_nearby", "entity", "minecraft:cow", "radius", 8)));
        assertTrue(WorldFactConditions.valid(c("type", "entity_nearby", "entity", "#minecraft:raiders", "minimum", 2)));
        assertTrue(WorldFactConditions.valid(c("type", "block_at", "x", 100, "y", 64, "z", 200, "block", "minecraft:crafting_table")));
        assertTrue(WorldFactConditions.valid(c("type", "block_at", "x", 1, "y", 2, "z", 3, "block", "#minecraft:logs")));
        assertTrue(WorldFactConditions.valid(c("type", "container_at", "x", 100, "y", 64, "z", 200, "item", "minecraft:diamond", "minimum", 1)));
    }

    @Test
    void coordWorldFactTypesRejectMalformed() {
        assertFalse(WorldFactConditions.valid(c("type", "entity_nearby")), "缺 entity");
        assertFalse(WorldFactConditions.valid(c("type", "entity_nearby", "entity", "minecraft:cow", "radius", 64)), "半径越界");
        assertFalse(WorldFactConditions.valid(c("type", "block_at", "x", 1, "y", 2, "block", "minecraft:stone")), "缺 z");
        assertFalse(WorldFactConditions.valid(c("type", "block_at", "x", 1, "y", 2, "z", 3)), "缺 block");
        assertFalse(WorldFactConditions.valid(c("type", "container_at", "x", 1, "y", 2, "z", 3)), "缺 item");
    }

    @Test
    void missingOrNonStringTypeIsRejected() {
        assertFalse(WorldFactConditions.valid(null));
        assertFalse(WorldFactConditions.valid(Map.of()), "空条件不是世界事实");
        assertFalse(WorldFactConditions.valid(c("type", 7)), "type 必须是字符串");
        assertFalse(WorldFactConditions.valid(c("type", List.of("base"))), "type 必须是字符串而不是集合");
    }

    // ── ② 背包类条件不属于世界事实 ────────────────────────────────

    @Test
    void assetKeyOrGroupMakesItNotAWorldFact() {
        assertFalse(WorldFactConditions.valid(c("type", "base", "asset_key", "minecraft:chest")),
                "★ 带 asset_key 的条件走背包判定器，不许同时被当世界事实 —— 两个真身打架 = 判据语义漂移");
        assertFalse(WorldFactConditions.valid(c("type", "base", "group", "food")),
                "带 group 的条件走分组计数，不许同时被当世界事实");
        assertFalse(WorldFactConditions.valid(c("type", "inventory", "asset_key", "minecraft:stone", "minimum", 1)));
    }

    // ── ③ 每个 type 各自的必填项 ───────────────────────────────────

    @Test
    void advancementRequiresAValidAdvancementId() {
        assertTrue(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root")));
        assertFalse(WorldFactConditions.valid(c("type", "advancement")),
                "advancement 没有 id = 不知道要查哪个成就 = 永远判不了");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "")));
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "story/root")),
                "id 必须是 [命名空间:路径] 形状，裸路径会让查询永远落空");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "Minecraft:Story/Root")),
                "命名空间必须全小写（资源 id 形状校验）");
    }

    @Test
    void structureRequiresAValidStructureId() {
        assertTrue(WorldFactConditions.valid(c("type", "structure", "structure", "minecraft:village_plains")));
        assertFalse(WorldFactConditions.valid(c("type", "structure")));
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "village_plains")));
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "minecraft:")));
    }

    // ── ④ 2026-10-02 新增：精确 id 与 #tag 同等支持 ────────────────────
    // 原版里「一个东西好几个变体」是常态：村庄五个 id、海底废墟冷暖两版、
    // 「任意森林」是一整个 biome tag。规划器逐个枚举变体必漏，漏一个就静默判假。

    @Test
    void structureAcceptsTagsAsWellAsExactIds() {
        assertTrue(WorldFactConditions.valid(c("type", "structure", "structure", "#minecraft:village")),
                "「到了任意村庄」必须能用一个 tag 表达，否则规划器要枚举五个变体");
        assertTrue(WorldFactConditions.valid(c("type", "structure", "structure", "#minecraft:ruined_portal")));
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "#")),
                "只有 # 没有 id = 空 tag");
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "#village")),
                "tag 也必须带命名空间");
        assertFalse(WorldFactConditions.valid(c("type", "structure", "structure", "#Minecraft:Village")));
    }

    @Test
    void biomeRequiresABiomeIdOrTag() {
        assertTrue(WorldFactConditions.valid(c("type", "biome", "biome", "minecraft:desert")));
        assertTrue(WorldFactConditions.valid(c("type", "biome", "biome", "#minecraft:is_forest")));
        assertFalse(WorldFactConditions.valid(c("type", "biome")), "biome 没有 id = 不知道查哪片地形");
        assertFalse(WorldFactConditions.valid(c("type", "biome", "biome", "desert")),
                "群系必须带命名空间（和结构一样）");
        assertFalse(WorldFactConditions.valid(c("type", "biome", "biome", "#Minecraft:is_forest")));
        // 多余键一律容忍：advancement / base / entity_killed 历史上都不拒额外键，
        // 只给 biome 加严会造成「同类条件宽严不一」，而且 parseOne 丢弃时不报错，模型无从排查。
        assertTrue(WorldFactConditions.valid(c("type", "biome", "biome", "minecraft:plains", "minimum", 1)),
                "★ 多余键容忍是全 type 一致的契约，biome 也不例外");
    }

    @Test
    void blockNearbyNeedsABlockAndABoundedRadius() {
        assertTrue(WorldFactConditions.valid(c("type", "block_nearby", "block", "minecraft:diamond_ore")));
        assertTrue(WorldFactConditions.valid(c("type", "block_nearby", "block", "#minecraft:ores", "radius", 16)));
        assertFalse(WorldFactConditions.valid(c("type", "block_nearby")),
                "block_nearby 没有 block = 不知道找什么方块");
        assertFalse(WorldFactConditions.valid(c("type", "block_nearby", "block", "diamond_ore")));
        assertFalse(WorldFactConditions.valid(c("type", "block_nearby", "block", "minecraft:diamond_ore", "radius", 0)),
                "半径 0 只能判自己脚下一格");
        assertFalse(WorldFactConditions.valid(c("type", "block_nearby", "block", "minecraft:diamond_ore", "radius", 64)),
                "★ 半径必须被硬顶住：判定每秒跑一次，放开就是每秒扫两百万格");
        assertFalse(WorldFactConditions.valid(c("type", "block_nearby", "block", "minecraft:diamond_ore", "radius", 8.5)));
    }

    @Test
    void containerNearbyNeedsAnItemAndPositiveMinimum() {
        assertTrue(WorldFactConditions.valid(c("type", "container_nearby", "item", "minecraft:wheat")));
        assertTrue(WorldFactConditions.valid(c("type", "container_nearby", "item", "minecraft:wheat", "minimum", 16)));
        assertTrue(WorldFactConditions.valid(c("type", "container_nearby", "item", "#minecraft:planks", "radius", 4)));
        assertFalse(WorldFactConditions.valid(c("type", "container_nearby")));
        assertFalse(WorldFactConditions.valid(c("type", "container_nearby", "item", "minecraft:wheat", "minimum", 0)),
                "★ minimum 必须为正：0 个 = 一开始就假完成");
        assertFalse(WorldFactConditions.valid(c("type", "container_nearby", "item", "minecraft:wheat", "minimum", -3)));
        assertFalse(WorldFactConditions.valid(c("type", "container_nearby", "item", "minecraft:wheat", "radius", 32)));
    }

    // ── ⑤ 宿主侧读取用的解析器（宿主不做形状校验，只取默认值）──────────

    @Test
    void tagHelpersStripTheHashAndRejectGarbage() {
        assertEquals("minecraft:village", WorldFactConditions.tagId("#minecraft:village"));
        assertEquals("minecraft:village", WorldFactConditions.tagId("minecraft:village"));
        assertNull(WorldFactConditions.tagId("#"));
        assertNull(WorldFactConditions.tagId(null));
        assertNull(WorldFactConditions.tagId(7));
        assertTrue(WorldFactConditions.isTag("#minecraft:village"));
        assertFalse(WorldFactConditions.isTag("minecraft:village"));
        assertFalse(WorldFactConditions.isTag(null));
    }

    @Test
    void radiusAndMinimumFallBackInsteadOfThrowing() {
        Map<String, Object> noRadius = c("type", "block_nearby", "block", "minecraft:stone");
        assertEquals(7, WorldFactConditions.radiusOf(noRadius, 7),
                "缺省值由调用方给 —— 宿主按 type 自己选 DEFAULT_BLOCK_RADIUS 还是 DEFAULT_CONTAINER_RADIUS");
        assertEquals(WorldFactConditions.DEFAULT_BLOCK_RADIUS, WorldFactConditions.radiusOf(noRadius, WorldFactConditions.DEFAULT_BLOCK_RADIUS));
        assertEquals(4, WorldFactConditions.radiusOf(c("radius", 4), 8));
        assertEquals(8, WorldFactConditions.radiusOf(c("radius", 999), 8), "越界半径回落，不许放任扫描");
        assertEquals(8, WorldFactConditions.radiusOf(c("radius", 0), 8), "半径 0 回落");
        assertEquals(8, WorldFactConditions.radiusOf(c("radius", "big"), 8));
        assertEquals(8, WorldFactConditions.radiusOf(null, 8));

        assertEquals(1, WorldFactConditions.minimumOf(c(), 1));
        assertEquals(12, WorldFactConditions.minimumOf(c("minimum", 12), 1));
        assertEquals(1, WorldFactConditions.minimumOf(c("minimum", 0), 1));
        assertEquals(1, WorldFactConditions.minimumOf(null, 1));
    }

    @Test
    void entityKilledRequiresEntityIdAndPositiveWholeMinimum() {
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie")));
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 1)));
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 20)));
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed")),
                "entity_killed 没有 entity id = 不知道数什么");
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 0)),
                "★ minimum 必须为正：0 个算「已达成」= 一开始就假完成");
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", -1)));
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", 1.5)),
                "★ minimum 必须是整数：1.5 个生物不存在，判据永远为假 = 静默卡死");
        assertFalse(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "minimum", "2")),
                "minimum 必须是数字，不能是字符串");
    }

    @Test
    void entityKilledMinimumDefaultsToOne() {
        // 不写 minimum = 默认 1（写 0 会被拒，所以默认值必须是 1 才自洽）
        assertTrue(WorldFactConditions.valid(c("type", "entity_killed", "entity", "minecraft:zombie", "optional", true)),
                "带无关字段不影响合法性；默认 minimum 应为 1");
    }

    @Test
    void baseNeedsNothing() {
        assertTrue(WorldFactConditions.valid(c("type", "base")),
                "base 无额外必填项（真实语义是「重生床双格完整 + 朝向一致 + 旁边站得下人」，见 RddWorldFacts.inspectBase）");
        assertTrue(WorldFactConditions.valid(c("type", "base", "dimension", "minecraft:overworld")));
    }

    // ── ④ dimension 是可选的，但给了就必须合法 ───────────────────────

    @Test
    void dimensionIsOptionalButMustBeWellFormedWhenPresent() {
        assertTrue(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root")));
        assertTrue(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", "minecraft:the_nether")));
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", "the_nether")), "dimension 也必须是 [命名空间:路径]");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", "")), "空 dimension 不得被当成「不限维度」");
        assertFalse(WorldFactConditions.valid(c("type", "advancement", "advancement", "minecraft:story/root",
                "dimension", 5)), "dimension 必须是字符串");
    }

    // ── ⑤ 护栏：knownType 与 valid 必须说同一件事 ───────────────────

    @Test
    void knownTypeAndValidNeverDisagreeOnTheTypeToken() {
        for (String type : PINNED_TYPES) {
            // 造一个「除了 type 之外什么都不给」的裸条件
            boolean known = WorldFactConditions.knownType(type);
            boolean valid = WorldFactConditions.valid(c("type", type));
            if (type.equals("base")) {
                assertTrue(known && valid, "base 既要 known 也要 valid");
            } else {
                assertTrue(known, type + " 必须 knownType");
                assertFalse(valid, type + " 缺必填 id 时 valid 必须为 false（两处对 type 本身看法必须一致）");
            }
        }
    }

    @Test
    void pinnedTypeListMatchesWhatKnownTypeAccepts() {
        // 把「我以为的清单」与「实现实际接受的」做一次对照。
        // 实现内部是一个内联 Set.of(...)，这里通过逐项探测 + 排除常见误加项来锁定。
        Set<String> accepted = new java.util.HashSet<>();
        for (String candidate : List.of("advancement", "structure", "entity_killed", "base",
                "biome", "block_nearby", "container_nearby",
                "block_mined", "item_crafted", "item_used", "item_picked_up",
                "dimension", "position_at", "y_below",
                "entity_nearby", "block_at", "container_at",
                "inventory", "asset", "item", "block", "entity", "recipe",
                "effect", "stat", "weather", "time", "damage", "xp", "level", "mob_nearby")) {
            if (WorldFactConditions.knownType(candidate)) accepted.add(candidate);
        }
        assertEquals(Set.copyOf(PINNED_TYPES), accepted,
                "★ 实现接受的 type 集合与本类钉住的清单不一致。要么有人加了新 type（请同步本类"
                        + "并同时检查 RddWorldFacts.matches 与 RddDecomposer 提示词），要么有人删了旧的。");
    }
}