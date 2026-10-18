package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.api.carrier.CarrierChain;
import com.dwinovo.numen.api.carrier.CarrierRuleStore;
import com.dwinovo.numen.api.carrier.CarrierRules;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 携带器审批流的离线检查（2026-10-05）。
 *
 * <p>重点守三件事：
 * <ol>
 *   <li><b>未批准就不生效</b> —— 这是审批流存在的全部理由；</li>
 *   <li><b>批准必须带理由</b> —— 没理由的批准无法追责；</li>
 *   <li><b>批准完真的生效</b> —— 「批准成功但运行时没变化」是最坏的哑故障。</li>
 * </ol>
 */
class CarrierApprovalFlowTest {

    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static Path tmp(String n) {
        return Path.of(System.getProperty("java.io.tmpdir"),
                "carrier-approval-" + n + "-" + System.nanoTime());
    }

    private static CarrierArtifactAdopter adopterWithDraft(Path dir, String body) {
        ArtifactOutbox ob = new ArtifactOutbox(dir.resolve("outbox"));
        ob.submit(ArtifactOutbox.Kind.CARRIER, C, "rev-1", "m-1", "my_carrier", body);
        return new CarrierArtifactAdopter(dir, ob);
    }

    private static String draftJson(String when, String... carry) {
        StringBuilder sb = new StringBuilder("{\"name\":\"my_carrier\",\"when\":\"" + when + "\",\"carry\":[");
        for (int i = 0; i < carry.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(carry[i]).append('"');
        }
        return sb.append("],\"fix\":[]}").toString();
    }

    @Test
    void submit_registersCandidate_butDoesNotTakeEffect() {
        Path dir = tmp("submit");
        // ★ 先 install 自己的目录：生效链是**静态**的（OVERRIDE），
        //   不先装就会读到上一个测试残留的规则 → 跨测试泄漏、结果与执行顺序有关。
        //   顺序与生产一致（LearnerPlugin.setup 也是先 install 再让 adopter 干活）。
        CarrierRuleStore.install(dir);
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));

        var rep = ad.adoptAll();
        assertEquals(1, rep.scanned(), "应当扫到 1 条草稿");
        assertEquals(1, rep.submitted(), "应当登记成 1 条候选");
        assertEquals(1, ad.candidates().size(), "候选表里应有 1 条");
        assertEquals("PENDING", ad.candidateStatus(rep.rows().get(0).artifactId()));

        // ★ 关键断言：还没批准 ⇒ 生效链里不许有它
        for (CarrierChain.Rule r : CarrierRuleStore.effective()) {
            assertFalse("my_carrier".equals(r.name()),
                    "没批准就进生效链 = 审批流形同虚设");
        }
    }

    @Test
    void approve_requiresReason() {
        Path dir = tmp("approve_requiresReason");
        CarrierRuleStore.install(dir);
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));
        var rep = ad.adoptAll();
        String id = rep.rows().get(0).artifactId();

        var e = assertThrows(IllegalArgumentException.class, () -> ad.approve(id, "  "));
        assertTrue(e.getMessage().contains("理由"),
                "报错要说清是缺理由，调用方才知道怎么改: " + e.getMessage());
        assertEquals("PENDING", ad.candidateStatus(id), "被拒的批准不许改变状态");
    }

    @Test
    void approve_takesEffectAndReloadWorks() {
        Path dir = tmp("effect");
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log", "torch"));
        var rep = ad.adoptAll();
        String id = rep.rows().get(0).artifactId();

        CarrierRuleStore.install(dir);
        var rule = ad.approve(id, "低血量时带木头照明，实机有用");

        assertEquals("my_carrier", rule.name());
        assertEquals(java.util.List.of("oak_log", "torch"), rule.carry());
        // 批准后必须真在生效链里 —— 否则就是「批准成功但游戏里没变化」
        boolean found = CarrierRuleStore.effective().stream()
                .anyMatch(r -> "my_carrier".equals(r.name()));
        assertTrue(found, "批准后规则必须在生效链里（否则审批是假的）");

        // 且谓词真能用：低血量成立、不成立两种都要验
        var low = CarrierChain.factsOf(Map.of("hp", "3"), "hp=3");
        assertTrue(rule.applies().test(low), "hp<=4 在 hp=3 时应当成立");
        var high = CarrierChain.factsOf(Map.of("hp", "20"), "hp=20");
        assertFalse(rule.applies().test(high), "hp<=4 在 hp=20 时不该成立");

        // 候选状态要变成 APPROVED，留痕
        assertEquals("APPROVED", ad.candidateStatus(id));
    }

    @Test
    void reject_marksRejected_keepsRecord_andDoesNotTakeEffect() {
        Path dir = tmp("reject");
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));
        var rep = ad.adoptAll();
        String id = rep.rows().get(0).artifactId();

        CarrierRuleStore.install(dir);
        ad.reject(id, "带木头不解决实际问题");

        assertEquals("REJECTED", ad.candidateStatus(id));
        assertEquals(1, ad.candidates().size(), "拒收不许删记录 —— 拒了要能回答「当时为什么拒」");
        for (CarrierChain.Rule r : CarrierRuleStore.effective()) {
            assertFalse("my_carrier".equals(r.name()), "拒收的规则绝不许进生效链");
        }
    }

    @Test
    void badDraft_isRejectedWithReason_notSilentlyDropped() {
        Path dir = tmp("bad");
        CarrierArtifactAdopter ad = adopterWithDraft(dir, "我想写个携带器，条件是血少的时候");
        var rep = ad.adoptAll();
        assertEquals(1, rep.scanned());
        assertEquals(0, rep.submitted(), "散文登记不了");
        assertEquals(1, rep.failed(), "失败必须计数，不许静默");
        assertEquals("REJECTED", rep.rows().get(0).status());
        assertTrue(rep.rows().get(0).detail().length() > 10, "拒收必须带原因: " + rep.rows().get(0));
    }

    @Test
    void draftWithoutWhen_isRejected() {
        // 没条件的规则会对所有情况生效 —— 那不是携带器，是全局开关
        var e = assertThrows(IllegalArgumentException.class,
                () -> CarrierRuleStore.Draft.parse("{\"name\":\"x\",\"carry\":[\"torch\"]}"));
        assertTrue(e.getMessage().contains("when"), "要说清缺 when: " + e.getMessage());
    }

    @Test
    void draftWithUnknownCondition_isRejected_notIgnored() {
        // 忽略不认识的词 = 规则在本不该生效时生效，比报错危险得多
        var e = assertThrows(IllegalArgumentException.class,
                () -> CarrierRuleStore.Draft.parse(draftJson("moon_is_full", "torch")));
        assertTrue(e.getMessage().contains("moon_is_full"),
                "报错要点名那个不认识的词: " + e.getMessage());
    }

    @Test
    void approve_sameNameTwice_failsLoudly() {
        Path dir = tmp("dup");
        CarrierRuleStore.install(dir);
        CarrierArtifactAdopter ad = adopterWithDraft(dir, draftJson("hp<=4", "oak_log"));
        var rep = ad.adoptAll();
        ad.approve(rep.rows().get(0).artifactId(), "第一次批准");

        var e = assertThrows(IllegalStateException.class,
                () -> ad.approve(rep.rows().get(0).artifactId(), "再批一次"));
        assertTrue(e.getMessage().contains("已批准过"), "重复批准要点名原因: " + e.getMessage());
    }

    // ── ★ 已批准规则必须真的被求值（2026-10-05 核查发现的实机缺口）────────────
    //
    // 默认链前几级（「指向谁」「装备」…）在多数实况下不成立，
    // 而 CarrierChain.evaluate 遇 !ok 就 break。
    // ⇒ 若把已批准规则追加在默认链**之后**，它**永远不会被求值**，
    //   而审批界面与报告都显示「已批准」。

    @Test
    void approvedRule_isActuallyEvaluated_afterDefaultChainWouldShortCircuit() {
        Path dir = tmp("order");
        CarrierRuleStore.install(dir);
        CarrierRuleStore.submitCandidate(dir, "a1", "{\"name\":\"bring_food\","
                + "\"when\":\"food<=5\",\"carry\":[\"cooked_beef\"]}", "rev", "m-1");
        CarrierRuleStore.approve(dir, "a1", "低饱食度时带吃的");

        var facts = CarrierChain.factsOf(Map.of("food", "3"), "food=3");
        var r = CarrierChain.evaluate(CarrierRuleStore.effective(), facts);

        assertTrue(facts.evaluatedLevels().contains("bring_food"),
                "★ 已批准的规则必须真的被求值；若排在默认链之后就会被短路吃掉。"
                        + "evaluated=" + facts.evaluatedLevels());
        assertTrue(r.why().contains("bring_food"), "why 里要能看到它: " + r.why());
        assertTrue(r.carry().contains("cooked_beef"),
                "它带的东西要真的进 carry: " + r.carry());
    }

    @Test
    void approvedRule_goesBeforeDefaults() {
        Path dir = tmp("order2");
        CarrierRuleStore.install(dir);
        CarrierRuleStore.submitCandidate(dir, "a1", "{\"name\":\"bring_food\","
                + "\"when\":\"food<=5\",\"carry\":[\"cooked_beef\"]}", "rev", "m-1");
        CarrierRuleStore.approve(dir, "a1", "低饱食度时带吃的");

        var chain = CarrierRuleStore.effective();
        int ours = -1;
        int firstDefault = -1;
        for (int i = 0; i < chain.size(); i++) {
            if ("bring_food".equals(chain.get(i).name())) {
                ours = i;
            }
            if (firstDefault < 0
                    && chain.get(i).name().equals(CarrierRules.DEFAULT.get(0).name())) {
                firstDefault = i;
            }
        }
        assertTrue(ours >= 0, "已批准的规则必须在链里");
        assertTrue(firstDefault >= 0, "默认链第一级必须在链里");
        assertTrue(ours < firstDefault,
                "★ 已批准规则必须排在默认链之前（人工批准 = 以这条为准）");
    }

    // ── ★ 已批准规则不得污染「读不懂 / 没有目标」的判据（2026-10-06）──────────

    /**
     * 回归护栏：{@code Memo.assessCarrier} 曾拿「链短路且 carry 为空」当
     * 「快照读不懂」的代理。已批准规则排在默认链之前 ⇒ 一条不成立且 {@code fix}
     * 为空的已批准规则会让链在第 0 级短路且 carry 为空 ⇒
     * <b>一份完全可读的快照被判成 UNKNOWN</b>（所有同伴的携带器目标一起变）。
     *
     * <p>症状最早以「假红」形式暴露：{@code CarrierChainTest} /
     * {@code CarrierSignalB21Test} / {@code EnvSnapshotFormatTest} 单跑全绿、
     * 与 {@code CarrierApprovalFlowTest} 合跑 6 条红（见 {@code CarrierStoreIsolation}）。
     */
    @Test
    void approvedRuleWithEmptyFix_doesNotFlipReadableSnapshotToUnknown() {
        Path dir = tmp("unknown-flip");
        CarrierRuleStore.install(dir);
        CarrierRuleStore.submitCandidate(dir, "a1", "{\"name\":\"bring_food\","
                + "\"when\":\"food<=5\",\"carry\":[\"cooked_beef\"]}", "rev", "m-1");
        CarrierRuleStore.approve(dir, "a1", "低饱食度时带吃的");

        // 该规则在 food=20 时不成立，草稿的 fix 为空 ⇒ 链在第 0 级短路且 carry 为空。
        Memo m = new Memo("m-1", "p", "s", "t",
                "hp=20/20, food=20, armor=none, weapon=none, hostile=false", 1L);
        Memo.CarrierAssessment a = m.assessCarrier();

        assertEquals("NONE", a.target(),
                "快照可读（hostile=false 是有效观测）⇒ 必须 NONE，"
                        + "不许被「已批准规则短路」污染成 UNKNOWN。why=" + a.why());
    }

    // ── ★ 坏行不许静默跳过（DL-14 / DL-15，2026-10-06）──────────────────────

    /**
     * 那条路是 {@code approve()} 的报错文案<b>自己指过去</b>的：
     * 「要改就人工编辑 approved.jsonl」。用户照做、打错一个 when 词
     * （{@code low_hp} 写成 {@code lowhp}）⇒ 原实现静默 {@code continue} ⇒
     * 规则从生效链消失，而候选表里它仍显示 {@code APPROVED}。
     */
    @Test
    void brokenApprovedLine_isCountedAndNamed_notSilentlyDropped() throws Exception {
        Path dir = tmp("broken-approved");
        CarrierRuleStore.install(dir);
        CarrierRuleStore.submitCandidate(dir, "a1",
                "{\"name\":\"good_rule\",\"when\":\"hostile\",\"carry\":[\"战斗经验\"]}", "rev", "m-1");
        CarrierRuleStore.approve(dir, "a1", "先批一条好的");

        // 手工追加一行坏 when —— 正是 approve() 文案指引用户去做的那件事。
        Path f = dir.resolve("carriers").resolve("approved.jsonl");
        Files.writeString(f, "{\"name\":\"typo_rule\",\"when\":\"lowhp\",\"carry\":[\"食物\"]}\n",
                java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        CarrierRuleStore.reload();

        var rep = CarrierRuleStore.lastLoad();
        assertEquals(1, rep.broken(), "坏行必须被计数（原来完全静默）: " + rep.reasons());
        assertEquals(1, rep.loaded(), "好的那条仍要生效 —— 一条坏行不许拖垮别的");
        assertTrue(String.valueOf(rep.reasons()).contains("typo_rule"),
                "要点名是哪条规则坏了: " + rep.reasons());
        assertTrue(String.valueOf(rep.reasons()).contains("lowhp"),
                "要点名是哪个词不认（否则用户不知道改哪里）: " + rep.reasons());
        assertTrue(CarrierRuleStore.effective().stream().anyMatch(r -> "good_rule".equals(r.name())),
                "好规则必须还在生效链里");
    }
}