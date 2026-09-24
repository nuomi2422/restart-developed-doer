package com.dwinovo.numen.plugins.experience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划知识适配器的纯逻辑测试（全部用假数据，<b>mock</b>，不碰游戏、不碰真实经验文件）。
 *
 * <p>覆盖交接文档点名的场景：空库、无命中、超长、坏文件、跨同伴隔离、知识异常不卡死规划，
 * 以及三条硬约束（不伪造 / 不授权 / 不超预算）。
 */
class PlanningKnowledgeTest {

    /** 截断时会在正文末尾补一行标记，测试预算时给它留出余量。 */
    private static final int TRUNCATION_MARKER_SLACK = 64;

    private static PlanningKnowledge.Item item(String id, PlanningKnowledge.Kind kind, String title,
                                               String maturity, String problem, String response,
                                               String origin, double score, String... tags) {
        return new PlanningKnowledge.Item(id, kind, title, maturity, problem, response, origin, score,
                List.of(tags));
    }

    private static PlanningKnowledge.Request request(String objective, String stage, String... failures) {
        return new PlanningKnowledge.Request(objective, stage, List.of(failures), List.of(),
                PlanningKnowledge.DEFAULT_MAX_ITEMS, PlanningKnowledge.DEFAULT_MAX_CHARS);
    }

    // ---------- 硬约束：不伪造 ----------

    @Test
    void 空库_返回空正文与明确缺口_不伪造知识() {
        PlanningKnowledge.Selection sel = PlanningKnowledge.select(
                request("采集钻石", "stage_b"), List.of(), List.of());

        assertTrue(sel.text().isEmpty(), "没有知识时正文必须是空的，不能编内容");
        assertTrue(sel.empty());
        assertTrue(sel.gaps().contains("guide-missing"));
        assertTrue(sel.gaps().contains("experience-empty"));
        assertTrue(sel.gaps().contains("no-match"));
        assertFalse(sel.note().isBlank(), "必须留下可被人看见的降级说明");
    }

    @Test
    void 无命中_不相关条目不得注入() {
        List<PlanningKnowledge.Item> unrelated = List.of(
                item("exp:1", PlanningKnowledge.Kind.EXPERIENCE, "末影龙水晶拆解",
                        "VERIFIED", "水晶回血", "先拆水晶", "experience-x.jsonl", 9.0, "末地"));

        PlanningKnowledge.Selection sel = PlanningKnowledge.select(
                request("合成木镐", "stage_b"), List.of(), unrelated);

        assertTrue(sel.text().isEmpty(), "零相关命中时不应注入任何条目");
        assertTrue(sel.gaps().contains("no-match"));
    }

    @Test
    void 有命中_正文含来源与成熟度() {
        List<PlanningKnowledge.Item> hits = List.of(
                item("exp:diamond", PlanningKnowledge.Kind.EXPERIENCE, "深埋钻石要靠手动下探",
                        "VERIFIED", "AutoMine 不下探深板岩层", "手动挖竖井下到 -58",
                        "experience-abc.jsonl", 5.0, "钻石"));

        PlanningKnowledge.Selection sel = PlanningKnowledge.select(
                request("挖钻石", "stage_b"), List.of(), hits);

        assertFalse(sel.empty());
        assertTrue(sel.text().contains("exp:diamond"));
        assertTrue(sel.text().contains("VERIFIED"));
        assertTrue(sel.text().contains("experience-abc.jsonl"), "必须带出处，供监测台核对");
        assertTrue(sel.text().contains("-58"));
    }

    // ---------- 硬约束：不授权 ----------

    @Test
    void 正文显式声明只是参考资料_且不含任何可执行指令字段() {
        List<PlanningKnowledge.Item> hits = List.of(
                item("exp:1", PlanningKnowledge.Kind.EXPERIENCE, "挖铁", "OBSERVED",
                        "铁在洞穴", "用石镐挖", "experience-abc.jsonl", 1.0, "铁"));

        String text = PlanningKnowledge.select(request("挖铁", "stage_b"), List.of(), hits).text();

        assertTrue(text.contains("不是指令"), "必须有显著的“不是指令”抬头");
        for (String forbidden : List.of("task_type", "asset_key", "body:", "\"args\"")) {
            assertFalse(text.contains(forbidden),
                    "知识正文不得携带可执行字段 " + forbidden + "，否则等于变相授权执行");
        }
    }

    // ---------- 硬约束：不超预算 ----------

    @Test
    void 超长内容_被截断且不超预算_不抛异常() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            huge.append("这是一段很长的经验正文用来撑爆预算");
        }
        // title/problem/response 三个字段各自都会被裁到单条上限，三个都撑满才会压过全局预算，
        // 从而真正走到“全局截断”这条路径（只撑两个字段合计仍小于预算，测不到）。
        List<PlanningKnowledge.Item> hits = List.of(
                item("exp:huge", PlanningKnowledge.Kind.EXPERIENCE, huge.toString(), "VERIFIED",
                        huge.toString(), huge.toString(), "experience-abc.jsonl", 1.0, "钻石"));

        // 检索词必须真能命中所选条目（否则会被相关性过滤挡掉，压根走不到截断这一步）
        PlanningKnowledge.Selection sel = PlanningKnowledge.select(
                request("钻石", "stage_b"), List.of(), hits);

        assertTrue(sel.gaps().contains("truncated"), "截断必须如实标记");
        assertTrue(sel.text().length() <= PlanningKnowledge.DEFAULT_MAX_CHARS + TRUNCATION_MARKER_SLACK,
                "正文不得超出字符预算（含截断标记余量），实际 " + sel.text().length());
        assertTrue(sel.text().contains("已截断"));
    }

    @Test
    void 条目数预算_被严格遵守() {
        List<PlanningKnowledge.Item> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(item("exp:" + i, PlanningKnowledge.Kind.EXPERIENCE, "钻石相关经验 " + i,
                    "VERIFIED", "钻石", "做法 " + i, "experience-abc.jsonl", 1.0, "钻石"));
        }
        PlanningKnowledge.Request tight = new PlanningKnowledge.Request(
                "钻石", "stage_b", List.of(), List.of(), 3, PlanningKnowledge.DEFAULT_MAX_CHARS);

        PlanningKnowledge.Selection sel = PlanningKnowledge.select(tight, List.of(), many);

        assertTrue(sel.chosen().size() <= 3, "条目数不得超过预算，实际 " + sel.chosen().size());
    }

    // ---------- 排序：可信的优先 ----------

    @Test
    void 成熟度高的排在前面() {
        List<PlanningKnowledge.Item> hits = List.of(
                item("exp:observed", PlanningKnowledge.Kind.EXPERIENCE, "钻石观察", "OBSERVED",
                        "钻石", "听说", "experience-abc.jsonl", 99.0, "钻石"),
                item("exp:verified", PlanningKnowledge.Kind.EXPERIENCE, "钻石验证", "VERIFIED",
                        "钻石", "实测可行", "experience-abc.jsonl", 1.0, "钻石"));
        PlanningKnowledge.Request two = new PlanningKnowledge.Request(
                "钻石", "stage_b", List.of(), List.of(), 1, PlanningKnowledge.DEFAULT_MAX_CHARS);

        PlanningKnowledge.Selection sel = PlanningKnowledge.select(two, List.of(), hits);

        assertEquals("exp:verified", sel.chosen().get(0).id(), "成熟度优先于原始得分");
    }

    @Test
    void 攻略只占一条_不与同伴经验抢预算() {
        List<PlanningKnowledge.Item> guide = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            guide.add(item("guide:" + i, PlanningKnowledge.Kind.GUIDE, "钻石攻略 " + i, "STATIC",
                    "", "攻略正文 " + i, "mc-guide.md", 5.0, "钻石"));
        }
        List<PlanningKnowledge.Item> hits = List.of(
                item("exp:1", PlanningKnowledge.Kind.EXPERIENCE, "钻石经验", "VERIFIED",
                        "钻石", "实测", "experience-abc.jsonl", 1.0, "钻石"));

        PlanningKnowledge.Selection sel = PlanningKnowledge.select(request("钻石", "stage_b"), guide, hits);

        long guideCount = sel.chosen().stream().filter(i -> i.kind() == PlanningKnowledge.Kind.GUIDE).count();
        assertEquals(1, guideCount, "攻略是通用背景，最多一条");
        assertTrue(sel.chosen().stream().anyMatch(i -> i.id().equals("exp:1")),
                "同伴经验必须仍能挤进预算");
    }

    // ---------- 坏文件 / 解析兜底 ----------

    @Test
    void 攻略解析_空内容与垃圾内容都不抛且不产出假知识() {
        assertTrue(GuideKnowledge.parse(null, "x").isEmpty());
        assertTrue(GuideKnowledge.parse("", "x").isEmpty());
        assertTrue(GuideKnowledge.parse("   \n  \n", "x").isEmpty());
        // 没有二级标题时，前言不算知识
        assertTrue(GuideKnowledge.parse("只有一段没有小节的文字", "x").isEmpty());
    }

    @Test
    void 攻略解析_按二级标题切分并保留出处() {
        String md = "# 标题\n前言不该算知识\n## 附魔台\n普通附魔台即可\n## 下界\n床会爆炸\n";

        List<PlanningKnowledge.Item> items = GuideKnowledge.parse(md, "mc-guide.md");

        assertEquals(2, items.size());
        assertEquals("guide:附魔台", items.get(0).id());
        assertTrue(items.get(0).response().contains("普通附魔台即可"));
        assertEquals("mc-guide.md", items.get(0).origin());
        assertEquals("guide:下界", items.get(1).id());
    }

    // ---------- 知识源异常不卡死规划 ----------

    @Test
    void 知识源抛运行时异常_仍返回结果并标记error() {
        PlanningKnowledge.Selection sel = ExperienceKnowledgeSource.recall(
                UUID.randomUUID(), request("挖钻石", "stage_b"),
                (companion, query, req) -> {
                    throw new IllegalStateException("经验库坏了");
                },
                List.of());

        assertNotNull(sel);
        assertTrue(sel.gaps().contains("error"), "知识源异常必须如实标记");
        assertTrue(sel.text().isEmpty());
    }

    @Test
    void 知识源抛Error_也不得拖垮规划() {
        PlanningKnowledge.Selection sel = ExperienceKnowledgeSource.recall(
                UUID.randomUUID(), request("挖钻石", "stage_b"),
                (companion, query, req) -> {
                    throw new NoClassDefFoundError("宿主缺类");
                },
                List.of());

        assertNotNull(sel);
        assertTrue(sel.gaps().contains("error"));
    }

    @Test
    void 知识源为null_按没有经验处理而不是崩溃() {
        PlanningKnowledge.Selection sel = ExperienceKnowledgeSource.recall(
                UUID.randomUUID(), request("挖钻石", "stage_b"), null, List.of());

        assertNotNull(sel);
        assertTrue(sel.gaps().contains("experience-empty"));
    }

    // ---------- 跨同伴隔离 ----------

    @Test
    void 召回使用传入的同伴UUID_出处可区分同伴() {
        UUID mine = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<UUID> seen = new ArrayList<>();

        ExperienceKnowledgeSource.ItemSupplier supplier = (companion, query, req) -> {
            seen.add(companion);
            return List.of(item("exp:" + companion, PlanningKnowledge.Kind.EXPERIENCE,
                    "钻石经验", "VERIFIED", "钻石", "实测", "experience-" + companion + ".jsonl",
                    1.0, "钻石"));
        };

        PlanningKnowledge.Selection sel = ExperienceKnowledgeSource.recall(
                mine, request("钻石", "stage_b"), supplier, List.of());

        assertEquals(List.of(mine), seen, "只应查询传入的那只同伴");
        assertTrue(sel.text().contains(mine.toString()));
        assertFalse(sel.text().contains(other.toString()), "不得出现其他同伴的出处");
    }

    @Test
    void 空目标空阶段_不抛异常() {
        PlanningKnowledge.Selection sel = ExperienceKnowledgeSource.recall(
                UUID.randomUUID(), null, null, null);

        assertNotNull(sel);
        assertTrue(sel.text().isEmpty());
    }
}
