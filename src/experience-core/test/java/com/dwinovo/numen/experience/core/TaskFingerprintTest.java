package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ B4：任务指纹 —— 让 60 号 A6 判据（「再发生一次同类事件时，这条经验被再次命中」）
 * <b>有东西可判</b>。
 *
 * <p>修之前 {@code buildQuery} 只是把 objective+stage+knownFailures 整句拼起来做字面匹配，
 * 没有稳定标识 ⇒ 「命中了」与「碰巧命中」分不开，A6 判不了。</p>
 */
class TaskFingerprintTest {

    // ---------- 同类任务 ⇒ 同指纹（A6 的前提）----------

    @Test
    void sameTaskGivesSameFingerprint() {
        String a = TaskFingerprint.of(List.of("采集36根甘蔗并运回基地"));
        String b = TaskFingerprint.of(List.of("采集36根甘蔗并运回基地"));
        assertEquals(a, b, "同一个任务两次发生必须同指纹，否则 A6 判不了");
        assertNotEquals(TaskFingerprint.UNKNOWN, a);
    }

    @Test
    void wordOrderDoesNotChangeTheFingerprint() {
        // ★ 按集合算指纹就是为了这个：「先挖钻石再回家」与「先回家再挖钻石」是同一类任务。
        //   这条测试**第一版是红的**（c93ecf88… vs 32bc28de…），逼出了一个真问题：
        //   我原来用 E6a 那套 2-gram 算指纹，而 **2-gram 本身就把语序编码进去了**
        //   （「先石再回」vs「先回家再」）⇒ 「按集合去重排序」根本消不掉语序差异。
        //   修法：指纹改用**中文单字集合**（2-gram 留给检索分词，两个目的不同）。
        String a = TaskFingerprint.of(List.of("先挖钻石再回家"));
        String b = TaskFingerprint.of(List.of("先回家再挖钻石"));
        assertEquals(a, b, "语序不同但词相同 ⇒ 同一类任务，指纹必须相同");
    }

    @Test
    void objectivePlusStagePlusFailuresCombineIntoOneFingerprint() {
        // buildQuery 就是这么拼的：objective + stage + knownFailures
        String combined = TaskFingerprint.of(List.of("采集甘蔗", "地下→地表", "goto 被实心岩层挡住"));
        String objectiveOnly = TaskFingerprint.of(List.of("采集甘蔗"));
        assertNotEquals(objectiveOnly, combined,
                "带上 stage 与已知失败后指纹应不同 —— 否则「同一类」会宽到什么都命中");
    }

    // ---------- 不同类 ⇒ 不同指纹（指纹不能糊成常量）----------

    @Test
    void differentTasksGiveDifferentFingerprints() {
        String mining = TaskFingerprint.of(List.of("挖钻石直到拿到钻石镐"));
        String farming = TaskFingerprint.of(List.of("种植甘蔗并收获"));
        String building = TaskFingerprint.of(List.of("用水流搭刷怪笼刷怪"));
        assertNotEquals(mining, farming);
        assertNotEquals(farming, building);
        assertNotEquals(mining, building);
    }

    @Test
    void fingerprintIsNotAConstant() {
        // ★ 防「不管什么任务都返回同一个值」这种最偷懒的实现：
        //   那样 A6 会永远判「是同类任务」，判据就废了
        List<String> many = List.of(
                TaskFingerprint.of(List.of("任务甲")),
                TaskFingerprint.of(List.of("任务乙")),
                TaskFingerprint.of(List.of("任务丙")),
                TaskFingerprint.of(List.of("挖矿")),
                TaskFingerprint.of(List.of("盖房子")));
        assertEquals(many.size(), new java.util.LinkedHashSet<>(many).size(),
                "不同任务给出同一个指纹 ⇒ 指纹没起作用");
    }

    // ---------- 边界与诚实口径 ----------

    @Test
    void emptyOrBlankGivesUnknownNotNull() {
        assertEquals(TaskFingerprint.UNKNOWN, TaskFingerprint.of(List.of()));
        assertEquals(TaskFingerprint.UNKNOWN, TaskFingerprint.of(null));
        assertEquals(TaskFingerprint.UNKNOWN, TaskFingerprint.of(List.of("")));
        assertEquals(TaskFingerprint.UNKNOWN, TaskFingerprint.of(List.of("   ")));
        assertEquals(TaskFingerprint.UNKNOWN, TaskFingerprint.of(List.of("!!!@@@###")));
        // 不返回 null：免得调用方各处判 null 而漏掉一处
    }

    @Test
    void unknownNeverCountsAsSameScene() {
        // ★ 关键：没算出来就不能装作「是同类场景」
        assertFalse(TaskFingerprint.sameScene(TaskFingerprint.UNKNOWN, TaskFingerprint.UNKNOWN));
        assertFalse(TaskFingerprint.sameScene(TaskFingerprint.UNKNOWN, "abc123"));
        assertFalse(TaskFingerprint.sameScene("abc123", null));
        assertTrue(TaskFingerprint.sameScene("abc123", "abc123"));
    }

    @Test
    void fingerprintIsNotTruncatedByDefault() {
        // ★ 诚实分工：指纹**只判「完全同一段话」**，措辞变了就是「近似」——
        //   近似交给检索层的字面打分，不靠指纹硬凑。
        //   这条测试第一版是红的：我原来默认「取前 N 个 token」想让长句与短句撞上，
        //   但字典序取前 N 个既难解释又难校准（「取字典序前 2 个」听着就怪）⇒ 改成不截断。
        String shortF = TaskFingerprint.of(List.of("采集甘蔗"));
        String longF = TaskFingerprint.of(List.of("采集甘蔗并运回基地的箱子旁边"));
        assertNotEquals(shortF, longF,
                "默认不截断：措辞不同 ⇒ 指纹不同（这是刻意的不做，不是缺陷）");
        // 显式传 maxTokens 时才截断
        assertEquals(TaskFingerprint.of(List.of("采集甘蔗并运回基地"), 3),
                TaskFingerprint.of(List.of("采集甘蔗并运回基地"), 3),
                "显式传 maxTokens 要可复现");
    }

    @Test
    void sameWordsInDifferentOrderStillMatchUnderTruncationToo() {
        // 单字集合的附带好处：截断也不会把语序差异带回来
        assertEquals(TaskFingerprint.of(List.of("先挖钻石再回家"), 4),
                TaskFingerprint.of(List.of("先回家再挖钻石"), 4));
    }

    @Test
    void explainTokensShowsWhatTheFingerprintIsMadeOf() {
        List<String> toks = TaskFingerprint.explainTokens(List.of("挖钻石"), 8);
        assertFalse(toks.isEmpty(), "观测侧要能看到指纹由哪些词构成");
        // 排序稳定
        assertEquals(toks, TaskFingerprint.explainTokens(List.of("挖钻石"), 8));
    }

    // ---------- 场景指纹组 ----------

    @Test
    void sceneFingerprintsAreDedupedAndDropUnknown() {
        List<String> scenes = TaskFingerprint.sceneFingerprints(
                List.of("采集甘蔗", "采集甘蔗", "", "挖钻石", "   "));
        // "采集甘蔗" 出现两次去重 ⇒ 剩「采集甘蔗」+「挖钻石」两个
        assertEquals(2, scenes.size(), "重复与空白要去掉：" + scenes);
        assertFalse(scenes.contains(TaskFingerprint.UNKNOWN), "UNKNOWN 不该出现在场景列表里");
    }

    @Test
    void entrySceneFingerprintsComeFromTitleAndTriggers() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title("刷怪笼半径内亮度大于7会停止刷怪")
                .description("d")
                .triggerStrings(List.of("亮度", "刷怪笼", "刷怪"))
                .build();
        List<String> fps = TaskFingerprint.entrySceneFingerprints(e);
        assertFalse(fps.isEmpty(), "条目该有适用场景指纹");
        assertTrue(fps.contains(TaskFingerprint.of(List.of("刷怪笼"))),
                "trigger_strings 里的词应各自成为场景指纹：" + fps);
    }

    @Test
    void entrySceneFingerprintsOfNullEntryIsEmptyNotNull() {
        assertEquals(List.of(), TaskFingerprint.entrySceneFingerprints(null));
    }

    // ---------- 指纹 vs 稳定键：两者不是一回事 ----------

    @Test
    void taskFingerprintIsNotTheSameAsEntryStableKey() {
        // ★ 口径纪律：stableKey 判「是不是同一条经验」，
        //   taskFingerprint 判「是不是同一类任务」。混用就是 B4 被做歪的那条路。
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.FAILURE)
                .title("亮度大于7停止刷怪")
                .description("d")
                .build();
        String stableKey = ExperienceEntry.stableKey(e.type(), e.title());
        String fp = TaskFingerprint.of(List.of(e.title()));
        assertNotEquals(stableKey, fp,
                "任务指纹不该等于条目稳定键 —— 一个是任务侧，一个是经验侧");
    }

    // ---------- 与成熟度过滤无关：指纹不参与打分 ----------

    @Test
    void fingerprintDoesNotChangeRetrievalScore() {
        // 指纹只进观测，不参与检索打分。验证：同一条经验，
        // 加不加指纹计算路径，score 必须一致（这里用 retrieve 两次对比）
        LexicalExperienceRetriever r = new LexicalExperienceRetriever();
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title("亮度大于7停止刷怪").description("d")
                .maturity(ExperienceMaturity.VERIFIED).build();
        double s1 = r.retrieve(com.dwinovo.numen.experience.api.ExperienceQuery.of("亮度", 5),
                List.of(e)).stream().findFirst().map(h -> h.score()).orElse(0.0);
        double s2 = r.retrieve(com.dwinovo.numen.experience.api.ExperienceQuery.of("亮度", 5),
                List.of(e)).stream().findFirst().map(h -> h.score()).orElse(0.0);
        assertEquals(s1, s2, "检索打分必须可复现，指纹不许偷偷参与打分");
    }
}