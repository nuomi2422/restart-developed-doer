package com.dwinovo.numen.experience.core;

import com.dwinovo.numen.experience.api.ExperienceEntry;
import com.dwinovo.numen.experience.api.ExperienceMaturity;
import com.dwinovo.numen.experience.api.ExperienceType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceEntryCodecTest {

    @Test
    void roundTripsThroughJson() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.TOOL_DEFECT)
                .title("假成功")
                .description("工具返回OK但世界没变")
                .rationale("防止AI被工具自述骗")
                .rootCause("工具只确认调用无异常")
                .recommendedResponse("操作后重新观察")
                .triggerStrings(List.of("假成功", "世界没变"))
                .toolNames(List.of("smelt_item"))
                .tags(List.of("world"))
                .maturity(ExperienceMaturity.VERIFIED)
                .verifiedCount(2)
                .priority(90)
                .counterexamples(List.of("有一次其实成功了"))
                .build();

        ExperienceEntry back = ExperienceEntry.fromJson(
                JsonParser.parseString(e.toJson().toString()).getAsJsonObject());
        assertEquals(e.id(), back.id());
        assertEquals(e.type(), back.type());
        assertEquals(e.title(), back.title());
        assertEquals(e.description(), back.description());
        assertEquals(e.rationale(), back.rationale());
        assertEquals(e.rootCause(), back.rootCause());
        assertEquals(e.recommendedResponse(), back.recommendedResponse());
        assertEquals(e.triggerStrings(), back.triggerStrings());
        assertEquals(e.toolNames(), back.toolNames());
        assertEquals(e.tags(), back.tags());
        assertEquals(e.maturity(), back.maturity());
        assertEquals(e.verifiedCount(), back.verifiedCount());
        assertEquals(e.priority(), back.priority());
        assertEquals(e.counterexamples(), back.counterexamples());
        assertEquals(e.createdAt(), back.createdAt());
        assertEquals(e.verifiedAt(), back.verifiedAt());
    }

    @Test
    void fromJsonToleratesMissingFields() {
        ExperienceEntry e = ExperienceEntry.fromJson(new JsonObject());
        assertEquals(ExperienceMaturity.OBSERVED, e.maturity());
        assertEquals(50, e.priority());
        assertTrue(e.triggerStrings().isEmpty());
        assertNull(e.type());
    }

    @Test
    void buildAssignsStableIdAndNormalizesBlanks() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.POLICY)
                .title(" 先查后写  ")
                .description("  动手前先确认问题没被解决过  ")
                .build();
        assertEquals(ExperienceEntry.stableKey(ExperienceType.POLICY, " 先查后写  "), e.id());
        assertEquals("先查后写", e.title());
        assertEquals("动手前先确认问题没被解决过", e.description());
    }

    // ============================================================
    // 七字段槽位的往返与读数（2026-10-04 补）
    // ============================================================

    /**
     * 七个结构化槽位必须逐字往返 —— <b>从 {@code fromJson} 那一侧钉</b>。
     *
     * <p>为什么必须专门写：{@code roundTripsThroughJson} 断言了 17 个字段，
     * <b>七个槽位一个都没断言、一个都没赋值</b>（builder 默认空串 ⇒ 就算断言也是
     * {@code "" == ""} 的恒真断言）。而 {@code toJson()} 那一侧另有契约测试
     * （{@code ExperienceDraftKeysBindToRealEntryTest}）按集合相等钉住键名。
     * 两头都有门、<b>中间那一段没有</b>：把 {@code fromJson} 里的
     * {@code str(o, "mechanism")} 改成任何别的键名或删掉，
     * 全仓<b>没有一个测试会红</b>，磁盘上所有七字段会静默读成空串。
     *
     * <p>样本刻意给<b>前后空格 + 内部多空格 + 全角标点</b>：
     * 这几个槽位走的是 {@code nz}/{@code nzForJson}（<b>只做 null→""，不 trim</b>），
     * 与 {@code title} 的处理不同。把这个差别钉住，是因为「盘上原样」与「盘上被悄悄改了」
     * 在下游读起来一模一样，而后者会让「学习者原话」变成「我们润色过的话」。
     * 断言一律用<b>精确相等</b>，不用 {@code contains}（那条我自己第一版写错过：
     * 一串 {@code ||} 的析取断言几乎恒真，且失败时报不出是哪个槽位坏了）。
     */
    @Test
    void sevenSlotsSurviveTheJsonRoundTripUnchanged() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.WORLD_RELATION)
                .title("水先铺再挖")
                .description("挖黑曜石前先把水流下去")
                .mechanism(" 岩浆边的方块没有支撑会  下落，  水能提供临时支撑。")
                .preconditions(" 镐等级 ≥ 该方块 tier；且  脚下 3 格有可站立面 ")
                .failureConditions(" 开阔熔岩湖上方；末影人直视范围内 ")
                .observableSignal(" HUD 血量 < 6 且无食物 → 停止下潜 ")
                .derivation(" 血量<6 → 上岸 → 找食物 → 血量≥8 再回 ")
                .efficiency(" 一次下潜 12 格 vs 3 格×4 次；时间 -70% ")
                .evidence(" ep7 t2000 commentary 原话 + 完整帧截图路径 ")
                .build();

        JsonObject wire = JsonParser.parseString(e.toJson().toString()).getAsJsonObject();
        // 先钉写侧：键必须真的在盘上，且值逐字落盘（否则下面 fromJson 的断言可能只是"两边都空"）。
        assertEquals(e.mechanism(), wire.get("mechanism").getAsString(), "机制必须逐字落盘");
        assertEquals(e.preconditions(), wire.get("preconditions").getAsString(), "前置条件必须逐字落盘");
        assertEquals(e.failureConditions(), wire.get("failure_conditions").getAsString(),
                "失效条件必须逐字落盘（键名是下划线式，与 learner 侧 ENTRY_KEYS 一致）");
        assertEquals(e.observableSignal(), wire.get("observable_signal").getAsString(),
                "可观察信号必须逐字落盘");
        assertEquals(e.derivation(), wire.get("derivation").getAsString(), "推导步骤必须逐字落盘");
        assertEquals(e.efficiency(), wire.get("efficiency").getAsString(), "效率必须逐字落盘");
        assertEquals(e.evidence(), wire.get("evidence").getAsString(),
                "证据必须逐字落盘（这一槽位以前在 learner 侧被说成「没有对应槽位」，"
                        + "那是过时注释：ExperienceEntry 早就有 evidence 槽位）");
        assertTrue(wire.has("mechanism") && wire.has("evidence"),
                "★ toJson() 必须写出七字段槽位本身（缺键时 fromJson 读不到，fromJson 的断言会假绿）");

        ExperienceEntry back = ExperienceEntry.fromJson(wire);
        assertEquals(e.mechanism(), back.mechanism());
        assertEquals(e.preconditions(), back.preconditions());
        assertEquals(e.failureConditions(), back.failureConditions());
        assertEquals(e.observableSignal(), back.observableSignal());
        assertEquals(e.derivation(), back.derivation());
        assertEquals(e.efficiency(), back.efficiency());
        assertEquals(e.evidence(), back.evidence());
        assertEquals(7, back.sevenFieldsFilled());
    }

    /** 反证：七槽全空时计数必须是 0，<b>不许</b>因为"七个键都在盘上"就数成 7。 */
    @Test
    void emptySlotsCountAsZeroNotAsSevenBecauseTheKeysExist() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.EXECUTION).title("t").description("d").build();
        ExperienceEntry back = ExperienceEntry.fromJson(
                JsonParser.parseString(e.toJson().toString()).getAsJsonObject());
        assertEquals(0, back.sevenFieldsFilled(),
                "★ toJson 永远写出这七个键（空串也算写），所以计数必须看**值**不看键 —— "
                        + "按键数会把 2026-10-04 之前那种「七个键都在、值全空」的断链状态读成合格");
    }

    /** 计数口径：空白（只有空格/制表/换行）算「没填」，与 toJson 的形状一致。 */
    @Test
    void blankIsNotFilledAndPartialCountsAreExact() {
        ExperienceEntry e = ExperienceEntry.builder()
                .type(ExperienceType.FAILURE).title("t").description("d")
                .mechanism("M").mechanism("")          // 覆盖成空
                .preconditions("   ")
                .derivation("D")
                .build();
        // mechanism 被空串覆盖、preconditions 只有空白 ⇒ 只有 derivation 算填了
        assertEquals(1, e.sevenFieldsFilled(),
                "★ 只有 derivation 非空 ⇒ 计数必须是 1（空串覆盖 + 纯空白都不算填）");
    }
}
