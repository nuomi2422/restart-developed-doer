package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守门器测试：模型违反硬规则时必须被拦住，而不是照单执行。
 */
class RddPlanGuardTest {

    private static SubtaskSpec spec(String description, String assetKey, int minimum) {
        return new SubtaskSpec(description, Map.of("asset_key", assetKey, "minimum", minimum), null);
    }

    // ---------- 1 & 3：未要求的可选升级被丢弃，明确要求时放行 ----------

    @Test
    void 未要求时书架与满级相关步骤被丢弃() {
        List<SubtaskSpec> plan = List.of(
                spec("开采黑曜石", "minecraft:obsidian", 4),
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("合成铁砧", "minecraft:anvil", 1));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", Map.of());

        assertEquals(1, out.allowed().size(), "只剩必要的黑曜石步骤");
        assertEquals("minecraft:obsidian",
                out.allowed().get(0).condition().get("asset_key"));
        assertEquals(2, out.dropped().size());
        assertTrue(out.dropped().stream().allMatch(d -> d.contains("可选升级未获要求")));
    }

    @Test
    void 主人明确要求时可选升级被放行() {
        List<SubtaskSpec> plan = List.of(
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("开采黑曜石", "minecraft:obsidian", 4));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "帮我做满级附魔台和15个书架", Map.of());

        assertEquals(2, out.allowed().size(), "已明确要求就不该再拦");
        assertTrue(out.dropped().isEmpty());
    }

    // ---------- 2：已持有资产不重复规划 ----------

    @Test
    void 已持有的资产步骤被去掉并记为复用() {
        List<SubtaskSpec> plan = List.of(
                spec("再挖6颗钻石", "minecraft:diamond", 6),
                spec("挖铁", "minecraft:raw_iron", 4));
        Map<String, Integer> held = Map.of("minecraft:diamond", 6, "minecraft:raw_iron", 1);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "深钻求钻", held);

        assertEquals(1, out.allowed().size(), "已持够的钻石步骤不该再规划");
        assertEquals("minecraft:raw_iron", out.allowed().get(0).condition().get("asset_key"));
        assertTrue(out.reused().contains("minecraft:diamond"));
    }

    @Test
    void 持有数量不足时仍然保留该步骤() {
        List<SubtaskSpec> plan = List.of(spec("挖煤", "minecraft:coal", 8));
        Map<String, Integer> held = Map.of("minecraft:coal", 3);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "采集燃料", held);

        assertEquals(1, out.allowed().size(), "不够就得继续采");
        assertTrue(out.reused().isEmpty());
    }

    // ---------- 6：违反硬规则时安全降级 ----------

    @Test
    void 整条计划全是违规时安全降级为空表() {
        List<SubtaskSpec> plan = List.of(
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("合成铁砧", "minecraft:anvil", 1));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", Map.of());

        assertTrue(out.degradedToEmpty(), "全部违规必须降级而不是照单执行");
        assertTrue(out.allowed().isEmpty());
        assertEquals(2, out.dropped().size());
    }

    @Test
    void 全部已持有时保留原计划_不制造空计划() {
        // 保护性让步：全已满足时若清空，会把「本来就做完的一级」变成一次升级上报
        List<SubtaskSpec> plan = List.of(spec("挖钻石", "minecraft:diamond", 6));
        Map<String, Integer> held = Map.of("minecraft:diamond", 6);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "深钻求钻", held);

        assertEquals(1, out.allowed().size(), "不得清空");
        assertTrue(out.reused().contains("minecraft:diamond"));
    }

    @Test
    void 违规可选与已持有混合时_违规项绝不复活() {
        List<SubtaskSpec> plan = List.of(
                spec("做15个书架", "minecraft:bookshelf", 15),
                spec("挖钻石", "minecraft:diamond", 6));
        Map<String, Integer> held = Map.of("minecraft:diamond", 6);

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", held);

        assertEquals(1, out.allowed().size());
        assertTrue(out.allowed().stream()
                        .noneMatch(s -> "minecraft:bookshelf".equals(s.condition().get("asset_key"))),
                "书架是违规项，不得因为「其余步骤全已持有」而被放回执行");
    }

    // ---------- Stage-A 主题过滤 ----------

    @Test
    void 未要求时可选工程阶段被整条丢弃() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("铁器时代：两把铁镐与盾牌", List.of()),
                new PrimarySpec("生电：刷铁机与村民交易所", List.of()),
                new PrimarySpec("下界之门：黑曜石与打火石", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "通关MC");

        assertEquals(2, out.allowed().size());
        assertEquals(1, out.dropped().size());
        assertTrue(out.dropped().get(0).contains("生电"));
    }

    @Test
    void 明确要求生电时阶段放行() {
        List<PrimarySpec> stages = List.of(new PrimarySpec("生电：刷铁机与村民交易所", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "帮我建生电和村民交易所");

        assertEquals(1, out.allowed().size());
        assertTrue(out.dropped().isEmpty());
    }

    @Test
    void 未要求时全部可选分支必须返回空阶段表用于安全降级() {
        // 回归：模型只给了可选分支，绝不能被「别清空」的保护放回执行
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("生电：刷铁机", List.of()),
                new PrimarySpec("村民交易所", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "通关MC");

        assertTrue(out.allowed().isEmpty(), "全为违规可选分支时必须返回空表，交给上游降级");
        assertEquals(2, out.dropped().size(), "丢弃原因要如实记录");
        assertTrue(out.dropped().stream().allMatch(d -> d.contains("可选分支未获要求")));
    }

    @Test
    void 可选分支与必要阶段混合时只丢可选() {
        List<PrimarySpec> stages = List.of(
                new PrimarySpec("生电：刷铁机", List.of()),
                new PrimarySpec("下界之门：黑曜石与打火石", List.of()));

        RddPlanGuard.Stages out = RddPlanGuard.filterStages(stages, "通关MC");

        assertEquals(1, out.allowed().size());
        assertEquals("下界之门：黑曜石与打火石", out.allowed().get(0).description());
    }

    // ---------- 7：原有路径不被破坏 ----------

    @Test
    void 正常计划原样通过() {
        List<SubtaskSpec> plan = List.of(
                spec("开采黑曜石", "minecraft:obsidian", 4),
                spec("合成附魔台", "minecraft:enchanting_table", 1));

        RddPlanGuard.Filtered out = RddPlanGuard.filterSubtasks(plan, "通关MC", Map.of());

        assertEquals(2, out.allowed().size(), "普通附魔台是必要前置，不该被当成可选升级丢掉");
        assertTrue(out.dropped().isEmpty());
        assertTrue(out.reused().isEmpty());
    }

    @Test
    void 空输入不炸() {
        assertTrue(RddPlanGuard.filterSubtasks(null, "通关MC", null).allowed().isEmpty());
        assertTrue(RddPlanGuard.filterSubtasks(List.of(), "通关MC", Map.of()).allowed().isEmpty());
        assertTrue(RddPlanGuard.filterStages(null, "通关MC").allowed().isEmpty());
        assertTrue(RddPlanGuard.filterStages(List.of(), "通关MC").allowed().isEmpty());
        assertTrue(RddPlanGuard.filterStages(List.of(), "通关MC").dropped().isEmpty());
    }
}
