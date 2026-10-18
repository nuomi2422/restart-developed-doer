package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fact.FactSnapshot;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第三批 N1 生产接线的纯逻辑测试（不碰 MC：只测可离线钉死的部分）。
 *
 * <p>钉的三件事：需求提取口径（含 group 与去重取大）、状态迁移只在"变化"时成立、
 * 渲染块对「持有未知 vs 缺口」不混用（DL-4：未知 ≠ 0）。
 */
class RddFactContextTest {

    private static PrimaryGoal goal() {
        return new PrimaryGoal("primary-acx-1", "测试一级：备料", List.of(
                Subtask.hardCoded("s1", "铁锭 x2", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 2)),
                Subtask.hardCoded("s2", "铁锭 x5", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 5)),
                Subtask.hardCoded("s3", "木头 x16", Map.of("group", "wood", "minimum", 16)),
                Subtask.hardCoded("s4", "未知组不跟踪", Map.of("group", "nope", "minimum", 3))),
                List.of(new AssetRequirement("minecraft:oak_log", 8)));
    }

    @Test
    void needsCoverWaitForSubtasksAndTakeMaxMinimum() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());

        assertEquals(3, needs.size(), "oak_log(waitFor) + iron(去重) + wood(group)；未知组不跟踪");
        assertEquals("minecraft:oak_log", needs.get(0).key(), "waitFor 排在最前");
        assertEquals(8, needs.get(0).minimum());
        assertEquals("minecraft:iron_ingot", needs.get(1).key());
        assertEquals(5, needs.get(1).minimum(), "同键两条取更大的 minimum");
        assertTrue(needs.get(2).group());
        assertEquals("group:wood", needs.get(2).display());
    }

    @Test
    void unmetUsesRealCountsAndGroupSums() {
        Map<String, Integer> held = Map.of(
                "minecraft:oak_log", 9,      // ≥8 满足
                "minecraft:iron_ingot", 1,   // <5 缺
                "minecraft:birch_log", 4);   // 计入 wood 组：9+4=13 <16 → 缺

        Set<String> unmet = RddFactContext.unmetKeys(RddFactContext.needsOf(goal()), held);
        assertEquals(Set.of("minecraft:iron_ingot", "group:wood"), unmet);
    }

    @Test
    void transitionOnlyFiresOnRealChanges() {
        assertEquals(RddFactContext.Transition.NONE, RddFactContext.transition(null, Set.of("a")),
                "第一次观察不报「变化」");
        assertEquals(RddFactContext.Transition.MET, RddFactContext.transition(Set.of("a"), Set.of()));
        assertEquals(RddFactContext.Transition.UNMET, RddFactContext.transition(Set.of(), Set.of("a")));
        assertEquals(RddFactContext.Transition.GAPS_CHANGED, RddFactContext.transition(Set.of("a"), Set.of("b")));
        assertEquals(RddFactContext.Transition.NONE, RddFactContext.transition(Set.of("a"), Set.of("a")));
        assertEquals(RddFactContext.Transition.NONE, RddFactContext.transition(Set.of(), Set.of()));
    }

    @Test
    void needsBlockListsOnlyUnmetAndIsBounded() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        Map<String, Integer> held = Map.of("minecraft:oak_log", 9, "minecraft:iron_ingot", 1, "minecraft:birch_log", 4);

        String block = RddFactContext.renderNeedsBlock(needs, held);
        assertTrue(block.startsWith("<needs>"), block);
        assertTrue(block.contains("minecraft:iron_ingot ×5（现有 1）"), block);
        assertTrue(block.contains("group:wood ×16（现有 13）"), block);
        assertFalse(block.contains("oak_log ×8"), "已满足的不该出现：" + block);
        assertTrue(block.endsWith("</needs>"));

        String allMet = RddFactContext.renderNeedsBlock(needs,
                Map.of("minecraft:oak_log", 8, "minecraft:iron_ingot", 5, "minecraft:birch_log", 16));
        assertEquals("", allMet, "全凑齐 → 整块不出现");

        StringBuilder many = new StringBuilder();
        List<Subtask> manySubs = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            manySubs.add(Subtask.hardCoded("m" + i, "需 " + i,
                    Map.of("asset_key", "minecraft:item_" + i, "minimum", 1)));
        }
        many.append(RddFactContext.renderNeedsBlock(
                RddFactContext.needsOf(new PrimaryGoal("primary-many", "多需求", manySubs)), Map.of()));
        assertTrue(many.toString().contains("…"), "超过上限要有省略号：" + many);
    }

    @Test
    void replanBlockSeparatesUnknownFromGap() {
        var manifest = new RequirementManifest.Manifest("goal-1", List.of(
                new RequirementManifest.Requirement("minecraft:crafting_table", 1, List.of()),
                new RequirementManifest.Requirement("minecraft:oak_log", 16, List.of())));
        CompletedFactStore facts = new CompletedFactStore();
        AssetRegistry assets = new AssetRegistry();
        assets.apply(new Observation("obs-1", "inventory_scan", "test", "minecraft:overworld", 1L,
                        Map.of("count", 0)),
                "minecraft:crafting_table", AssetScope.GLOBAL, null);

        FactSnapshot snapshot = FactSnapshot.capture(UUID.randomUUID(), "minecraft:overworld",
                facts, assets, null, 1_000L, 60_000L);
        String block = RddFactContext.renderReplanBlock(manifest, facts, assets, snapshot, 31_000L);

        assertTrue(block.contains("minecraft:crafting_table 需要 1，现有 0 → 缺口 1"), block);
        assertTrue(block.contains("minecraft:oak_log 需要 16，持有未知"), "没登记的键是「未知」不是 0：" + block);
        // ★ 2026-10-06 实机抓到的错标：未知行同时被渲染成「持有已达标」（attention=NONE 的文案）。
        //   未知行只许说未知，不许出现任何达标断言。
        String unknownLine = block.lines().filter(l -> l.contains("minecraft:oak_log")).findFirst().orElse("");
        assertFalse(unknownLine.contains("持有已达标"), "未知行不许渲染成达标：" + unknownLine);
        assertTrue(block.contains("只读陈述"), block);
        assertTrue(block.contains("刚刚采样"), "采样时间按注入的 now 渲染：" + block);

        assertEquals("", RddFactContext.renderReplanBlock(null, facts, assets, snapshot, 31_000L));
        assertEquals("", RddFactContext.renderReplanBlock(
                new RequirementManifest.Manifest("goal-1", List.of()), facts, assets, snapshot, 31_000L));
    }

    @Test
    void forPrimaryCarriesRealGoalIdNotPrimaryId() {
        var manifest = RddRequirementDetector.forPrimary(goal(), "goal-42");
        assertEquals("goal-42", manifest.goalId(),
                "事实库按 Goal.id 对账；填 primary.id 会让对账列永远 join 不上（2026-10-06 修）");
        // waitFor 也进清单（旧实现只收 subtask 的 asset_key）
        assertTrue(manifest.requirements().stream().anyMatch(r -> "minecraft:oak_log".equals(r.key())));
    }
}
