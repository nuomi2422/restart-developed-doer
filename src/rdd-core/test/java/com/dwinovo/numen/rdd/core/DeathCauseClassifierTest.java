package com.dwinovo.numen.rdd.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DeathCauseClassifier} 的单测。
 *
 * <p><b>为什么这组断言是"钉样本"而不是"钉想法"</b>：入参形态全部来自
 * 2026-10-01 实机日志里真实出现过的死因（5 种形态 + 原版 msgId 形态），
 * 不是从原版源码里推的。所以它们同时是回归网和"别再退回解析中文句子"的证据。
 */
class DeathCauseClassifierTest {

    // ── id 形态（与语言无关，优先走这条）───────────────────────────────

    @Test
    @DisplayName("原版 msgId → 归类（lava/drown/fall 等）")
    void idForms() {
        assertEquals(DeathCauseClassifier.LAVA, DeathCauseClassifier.classify("lava", null));
        assertEquals(DeathCauseClassifier.DROWN, DeathCauseClassifier.classify("drown", null));
        assertEquals(DeathCauseClassifier.FALL, DeathCauseClassifier.classify("fall", null));
        assertEquals(DeathCauseClassifier.SUFFOCATE, DeathCauseClassifier.classify("in_wall", null));
        assertEquals(DeathCauseClassifier.VOID, DeathCauseClassifier.classify("out_of_world", null));
        assertEquals(DeathCauseClassifier.STARVATION, DeathCauseClassifier.classify("starve", null));
        assertEquals(DeathCauseClassifier.LIGHTNING, DeathCauseClassifier.classify("lightningBolt", null));
    }

    @Test
    @DisplayName("爆炸排在怪物攻击之前 —— TNT 的 msgId 是 explosion，先于泛化攻击")
    void explosionBeforeMobAttack() {
        assertEquals(DeathCauseClassifier.EXPLOSION, DeathCauseClassifier.classify("explosion", null));
        // ★ 苦力怕在原版里的 msgId 是 mob_attack（它做的是实体伤害，不是爆炸伤害）。
        // 所以「被苦力怕炸死」从 id 上看就是 mob_attack → MOB，本测试钉住这个事实：
        // 想把「苦力怕炸死」单独分出来，需要的是**攻击者实体类型**，不是 msgId。
        assertEquals(DeathCauseClassifier.MOB, DeathCauseClassifier.classify("mob_attack", "被苦力怕炸死了"));
        // 没有 id 时，中文句子仍能把「炸」认成爆炸（换语言会退化，这是已知取舍）
        assertEquals(DeathCauseClassifier.EXPLOSION,
                DeathCauseClassifier.classify(null, "rdd被苦力怕炸死了"));
    }

    @Test
    @DisplayName("玩家攻击必须排在怪物之前（归因方向相反）")
    void playerBeforeMob() {
        assertEquals(DeathCauseClassifier.PLAYER, DeathCauseClassifier.classify("player_attack", null));
        assertEquals(DeathCauseClassifier.MOB, DeathCauseClassifier.classify("mob_attack", null));
    }

    @Test
    @DisplayName("id 认得 → 不用去看中文句子（哪怕中文指向别的）")
    void idWinsOverMessage() {
        // id 说 lava，就算中文句子写着"被苦力怕炸死了"也以 id 为准
        assertEquals(DeathCauseClassifier.LAVA, DeathCauseClassifier.classify("lava", "rdd被苦力怕炸死了"));
    }

    @Test
    @DisplayName("id 缺失或不认得 → 退化到中文句子，不抛异常")
    void fallsBackToMessage() {
        assertEquals(DeathCauseClassifier.MOB,
                DeathCauseClassifier.classify(null, "rdd被骷髅射杀"));
        assertEquals(DeathCauseClassifier.EXPLOSION,
                DeathCauseClassifier.classify("", "rdd被苦力怕炸死了"));
    }

    // ── 渲染后的中文句子形态（实测样本）─────────────────────────────────

    @Test
    @DisplayName("实测样本：5 种形态都归到正确的桶")
    void realMessageSamples() {
        assertEquals(DeathCauseClassifier.PLAYER, DeathCauseClassifier.classify(null, "rdd被maoshao2422杀死了"));
        assertEquals(DeathCauseClassifier.MOB, DeathCauseClassifier.classify(null, "rdd被骷髅射杀"));
        assertEquals(DeathCauseClassifier.EXPLOSION, DeathCauseClassifier.classify(null, "rdd被苦力怕炸死了"));
        assertEquals(DeathCauseClassifier.MOB, DeathCauseClassifier.classify(null, "rdd被女巫使用的魔法杀死了"));
    }

    @Test
    @DisplayName("48% 的无凶手兜底 → UNKNOWN，不是 OTHER（那是「规则没覆盖」）")
    void genericFallsToUnknown() {
        // 实测 579/1214 条长这样，占 48%。它不是「规则没覆盖」，是原版没给凶手 ——
        // 归因成任何具体原因都是编的，所以必须落在 UNKNOWN。
        assertEquals(DeathCauseClassifier.UNKNOWN, DeathCauseClassifier.classify(null, "rdd被杀死了"));
    }

    @Test
    @DisplayName("不认得的 id → OTHER（保留「见过新死因类型」这个信号，不被 UNKNOWN 吞掉）")
    void unknownVsOther() {
        assertEquals(DeathCauseClassifier.OTHER, DeathCauseClassifier.classify("some_new_damage_type", null));
        // 句子也没线索时仍保留 OTHER：OTHER 的价值就在于它能活到埋点那一步，好让人回头补规则。
        assertEquals(DeathCauseClassifier.OTHER, DeathCauseClassifier.classify("some_new_damage_type", "??"));
        // 什么都没有 → UNKNOWN（确实没有信息）
        assertEquals(DeathCauseClassifier.UNKNOWN, DeathCauseClassifier.classify(null, null));
        assertEquals(DeathCauseClassifier.UNKNOWN, DeathCauseClassifier.classify("   ", "  "));
    }

    @Test
    @DisplayName("★ 无凶手兜底句不能被判成 PLAYER（48% 的真实死亡）")
    void genericMustNotBecomePlayer() {
        // 「被杀死了」里 被 与 杀死 之间是空的 → 没有凶手 → UNKNOWN。
        // 这条是本次实机样本暴露的真实误判风险：48% 的死亡都是这个形态。
        assertEquals(DeathCauseClassifier.UNKNOWN, DeathCauseClassifier.classify(null, "rdd被杀死了"));
        assertEquals(DeathCauseClassifier.UNKNOWN, DeathCauseClassifier.classify(null, "rdd被殺死了"));
        // 对照：真带凶手名字的才判 PLAYER
        assertEquals(DeathCauseClassifier.PLAYER, DeathCauseClassifier.classify(null, "rdd被maoshao2422杀死了"));
    }

    @Test
    @DisplayName("永不抛异常：所有怪输入都返回一个合法桶")
    void neverThrows() {
        String[] ids = {null, "", "   ", "generic", "player_attack", "\u0000", "a".repeat(4096)};
        String[] msgs = {null, "", "  ", "rdd被杀死了", "\u0000\u0001", "被".repeat(1000)};
        for (String id : ids) {
            for (String msg : msgs) {
                String kind = DeathCauseClassifier.classify(id, msg);
                assertFalse(kind == null || kind.isBlank(), "must never return null/blank");
                assertEquals(kind, DeathCauseClassifier.classify(id, msg)); // 确定性：同输入同输出
            }
        }
    }

    @Test
    @DisplayName("大写/混合大小写 id 也认（原版 msgId 是驼峰的 lightningBolt）")
    void caseInsensitive() {
        assertEquals(DeathCauseClassifier.LIGHTNING, DeathCauseClassifier.classify("lightningBolt", null));
        assertEquals(DeathCauseClassifier.DROWN, DeathCauseClassifier.classify("DROWN", null));
    }

    @Test
    @DisplayName("仙人掌单独成桶（与窒息分开：那是「自己走错了」）")
    void cactusSeparateFromSuffocate() {
        assertEquals(DeathCauseClassifier.CACTUS, DeathCauseClassifier.classify("cactus", null));
        assertEquals(DeathCauseClassifier.SUFFOCATE, DeathCauseClassifier.classify("in_wall", null));
    }
}
