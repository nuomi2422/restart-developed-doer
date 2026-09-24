package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主聚合层的规划知识汇总测试：分隔符、总预算、故障隔离。
 *
 * <p>用纯函数 {@link NumenPlugins#aggregatePlanningKnowledge} 而不是全局注册表——
 * 注册表是静态的，测试之间无法隔离。
 */
class PlanningKnowledgeAggregationTest {

    private static final PlanningQuery QUERY =
            PlanningQuery.of(UUID.randomUUID(), "挖钻石", "stage_b", List.of());

    private static PlanningKnowledgeContributor fixed(String text) {
        return q -> text;
    }

    // ---------- 分隔符 ----------

    @Test
    void 多个贡献者之间用稳定分隔符连接_首尾不加() {
        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of(fixed("AAA"), fixed("BBB"), fixed("CCC")), QUERY);

        assertEquals("AAA" + NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR + "BBB"
                + NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR + "CCC", out);
        assertFalse(out.startsWith(NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR), "首部不得有分隔符");
        assertFalse(out.endsWith(NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR), "尾部不得有分隔符");
    }

    @Test
    void 空白片段被跳过_不产生连续分隔符() {
        String sep = NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR;
        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of(fixed("AAA"), fixed("   "), fixed(null), fixed("BBB")), QUERY);

        assertEquals("AAA" + sep + "BBB", out);
        assertFalse(out.contains(sep + sep), "空片段不得留下连续分隔符");
    }

    @Test
    void 同样输入两次结果逐字节一致() {
        List<PlanningKnowledgeContributor> contribs = List.of(fixed("A"), fixed("B"));

        assertEquals(NumenPlugins.aggregatePlanningKnowledge(contribs, QUERY),
                NumenPlugins.aggregatePlanningKnowledge(contribs, QUERY));
    }

    // ---------- 宿主总预算（不信任贡献者自觉限流） ----------

    @Test
    void 超出宿主总上限时截断() {
        int cap = NumenPlugins.MAX_PLANNING_KNOWLEDGE_CHARS;
        String oversized = "x".repeat(cap + 500);

        String out = NumenPlugins.aggregatePlanningKnowledge(List.of(fixed(oversized)), QUERY);

        assertEquals(cap, out.length(), "宿主必须自己把总量压在上限内");
    }

    @Test
    void 预算用尽后不再追加后续贡献者() {
        int cap = NumenPlugins.MAX_PLANNING_KNOWLEDGE_CHARS;
        String first = "a".repeat(cap);

        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of(fixed(first), fixed("SECOND-SHOULD-NOT-APPEAR")), QUERY);

        assertEquals(cap, out.length());
        assertFalse(out.contains("SECOND-SHOULD-NOT-APPEAR"), "预算已满就该停，不能再追加");
    }

    @Test
    void 多贡献者累加超预算时_前面完整保留_末尾截断() {
        int cap = NumenPlugins.MAX_PLANNING_KNOWLEDGE_CHARS;
        String head = "h".repeat(cap - 10);
        String tail = "t".repeat(200);

        String out = NumenPlugins.aggregatePlanningKnowledge(List.of(fixed(head), fixed(tail)), QUERY);

        assertEquals(cap, out.length());
        assertTrue(out.startsWith(head), "先到的贡献者应完整保留");
    }

    // ---------- 故障隔离 ----------

    @Test
    void 一个贡献者抛运行时异常不影响其他与整体() {
        PlanningKnowledgeContributor broken = q -> {
            throw new IllegalStateException("这个插件坏了");
        };

        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of(fixed("AAA"), broken, fixed("BBB")), QUERY);

        assertEquals("AAA" + NumenPlugins.PLANNING_KNOWLEDGE_SEPARATOR + "BBB", out,
                "坏掉的那一段丢掉，其余照常");
    }

    @Test
    void 一个贡献者抛Error也不影响其他() {
        PlanningKnowledgeContributor broken = q -> {
            throw new NoClassDefFoundError("缺类");
        };

        String out = NumenPlugins.aggregatePlanningKnowledge(
                List.of(broken, fixed("ALIVE")), QUERY);

        assertEquals("ALIVE", out);
    }

    @Test
    void 全部贡献者都坏时返回空串而不是抛出() {
        PlanningKnowledgeContributor broken = q -> {
            throw new RuntimeException("全坏");
        };

        assertEquals("", NumenPlugins.aggregatePlanningKnowledge(List.of(broken, broken), QUERY));
    }

    // ---------- 空输入 ----------

    @Test
    void 无贡献者或空查询返回空串() {
        assertEquals("", NumenPlugins.aggregatePlanningKnowledge(null, QUERY));
        assertEquals("", NumenPlugins.aggregatePlanningKnowledge(List.of(), QUERY));
        assertEquals("", NumenPlugins.aggregatePlanningKnowledge(List.of(fixed("A")), null));
    }
}
