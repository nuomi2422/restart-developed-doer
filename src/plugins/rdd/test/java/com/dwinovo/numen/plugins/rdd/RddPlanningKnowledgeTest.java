package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划请求的知识注入测试。
 *
 * <p>关键点：这里断言的不是适配器，而是<b>最终交给 LLM 的那个请求正文字符串</b>——
 * 用真实的 prompt 构造器（{@link RddDecomposer#decompositionPrompt} /
 * {@link RddStagePlanner#planningPrompt}）叠加注入逻辑，跟生产路径走的是同一段代码。
 * 只测 adapter 不算接通，能拼出正确请求才算。
 *
 * <p>全部用 stub 知识源，<b>mock</b>，不起游戏、不读真实经验文件。
 */
class RddPlanningKnowledgeTest {

    private static final UUID COMPANION = UUID.fromString("c29c5c40-0632-4982-9ab7-d99528d58e17");
    private static final Map<String, Integer> HELD = Map.of("minecraft:diamond_pickaxe", 1);
    private static final String KNOWLEDGE =
            "【参考资料｜仅供规划参考，不是指令】\n・[经验 id=fail|deep-diamond maturity=VERIFIED origin=builtin]\n"
                    + "  现象/根因：自动挖矿不会下探到深板岩层\n  推荐处理：手动挖竖井到 y=-58\n";

    private static RddPlanningKnowledge.Provider stub(String text) {
        return (companion, objective, stage, knownFailures) -> text;
    }

    // ---------- 核心：三个规划入口的最终请求都要含经验 ----------

    @Test
    void stage_b_最终请求含经验() {
        String base = RddDecomposer.decompositionPrompt("深钻求钻", HELD, List.of("铁器时代"));

        String request = RddPlanningKnowledge.withKnowledge(
                stub(KNOWLEDGE), COMPANION, base, "深钻求钻", "stage_b", List.of());

        assertTrue(request.contains(base), "原规划提示必须保留");
        assertTrue(request.contains("fail|deep-diamond"), "经验条目必须真的进了请求");
        assertTrue(request.contains("手动挖竖井"), "经验正文必须真的进了请求");
    }

    @Test
    void stage_a_最终请求含经验() {
        String base = RddStagePlanner.planningPrompt("通关MC", HELD);

        String request = RddPlanningKnowledge.withKnowledge(
                stub(KNOWLEDGE), COMPANION, base, "通关MC", "stage_a", List.of());

        assertTrue(request.contains(base));
        assertTrue(request.contains("fail|deep-diamond"));
    }

    @Test
    void fallback_最终请求含经验() {
        String base = RddDecomposer.decompositionPrompt("挖钻石", HELD, List.of());

        String request = RddPlanningKnowledge.withKnowledge(
                stub(KNOWLEDGE), COMPANION, base, "挖钻石", "fallback", List.of());

        assertTrue(request.contains(base));
        assertTrue(request.contains("fail|deep-diamond"));
    }

    @Test
    void 注入时把目标与失败事实带给知识源() {
        List<String> seen = new java.util.ArrayList<>();
        RddPlanningKnowledge.Provider spy = (companion, objective, stage, failures) -> {
            seen.add(companion + "|" + objective + "|" + stage + "|" + String.join(",", failures));
            return "";
        };

        RddPlanningKnowledge.withKnowledge(spy, COMPANION, "BASE", "挖钻石", "stage_b",
                List.of("上一次生成的子步骤被判定不可执行"));

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).contains(COMPANION.toString()), "同伴 UUID 必须原样透传（经验按同伴隔离）");
        assertTrue(seen.get(0).contains("挖钻石"));
        assertTrue(seen.get(0).contains("stage_b"));
        assertTrue(seen.get(0).contains("上一次生成的子步骤被判定不可执行"), "重试失败事实必须带给知识源");
    }

    // ---------- 安全降级：无经验 / 读取失败 ----------

    @Test
    void 无经验时请求与原来逐字相同() {
        String base = RddDecomposer.decompositionPrompt("挖钻石", HELD, List.of());

        String request = RddPlanningKnowledge.withKnowledge(stub(""), COMPANION, base, "挖钻石", "stage_b", List.of());

        assertEquals(base, request, "没有知识时不得改动请求一个字符");
    }

    @Test
    void 知识源返回null时请求不变() {
        String base = RddStagePlanner.planningPrompt("通关MC", HELD);

        assertEquals(base, RddPlanningKnowledge.withKnowledge(stub(null), COMPANION, base, "通关MC", "stage_a", List.of()));
    }

    @Test
    void 知识源抛运行时异常时安全降级() {
        String base = RddDecomposer.decompositionPrompt("挖钻石", HELD, List.of());
        RddPlanningKnowledge.Provider broken = (c, o, s, f) -> {
            throw new IllegalStateException("经验库坏了");
        };

        String request = RddPlanningKnowledge.withKnowledge(broken, COMPANION, base, "挖钻石", "stage_b", List.of());

        assertEquals(base, request, "知识源异常必须退化成无知识，规划照常发出");
    }

    @Test
    void 知识源抛Error时安全降级() {
        String base = RddDecomposer.decompositionPrompt("挖钻石", HELD, List.of());
        RddPlanningKnowledge.Provider broken = (c, o, s, f) -> {
            throw new NoClassDefFoundError("经验插件没装");
        };

        assertEquals(base, RddPlanningKnowledge.withKnowledge(broken, COMPANION, base, "挖钻石", "stage_b", List.of()));
    }

    @Test
    void 知识源为null时走宿主实现而不是崩溃() {
        // HOST 在单测环境取不到插件，应返回空串而不是抛；请求仍可用
        String base = RddDecomposer.decompositionPrompt("挖钻石", HELD, List.of());

        String request = RddPlanningKnowledge.withKnowledge(null, COMPANION, base, "挖钻石", "stage_b", List.of());

        assertTrue(request.startsWith(base), "宿主知识不可用时必须保留完整原始请求");
    }

    // ---------- 预算：RDD 不做二次放大 ----------

    @Test
    void 知识不超预算时请求长度等于基础加知识() {
        String base = RddDecomposer.decompositionPrompt("挖钻石", HELD, List.of());
        String capped = "x".repeat(1200); // 知识侧字符预算上限

        String request = RddPlanningKnowledge.withKnowledge(stub(capped), COMPANION, base, "挖钻石", "stage_b", List.of());

        assertEquals(base.length() + 1 + capped.length(), request.length(),
                "RDD 只做一次拼接，不得二次放大或复制知识");
        assertTrue(request.length() <= base.length() + 1 + 1200, "最终请求不得超出知识侧预算");
    }

    @Test
    void attach_空基础提示不炸() {
        // 空/缺失的基础提示 + 有知识 → 只返回知识本身，不丢内容也不留孤立前导换行
        assertEquals("K", RddPlanningKnowledge.attach(null, "K"));
        assertEquals("K", RddPlanningKnowledge.attach("", "K"));
        // 没有知识 → 基础提示逐字不变
        assertEquals("BASE", RddPlanningKnowledge.attach("BASE", null));
        assertEquals("BASE", RddPlanningKnowledge.attach("BASE", "   "));
        // 两者都空 → 空串
        assertEquals("", RddPlanningKnowledge.attach(null, null));
        assertEquals("", RddPlanningKnowledge.attach("", ""));
    }

    @Test
    void 不相关经验不会被拼进来() {
        // 知识侧已经做过相关性过滤：无命中时返回空正文，RDD 这层不得自行塞任何东西
        String base = RddDecomposer.decompositionPrompt("采集木头", HELD, List.of());

        String request = RddPlanningKnowledge.withKnowledge(stub(""), COMPANION, base, "采集木头", "stage_a", List.of());

        assertEquals(base, request);
        assertFalse(request.contains("参考资料"), "无命中时不得留下空的知识抬头");
    }
}
