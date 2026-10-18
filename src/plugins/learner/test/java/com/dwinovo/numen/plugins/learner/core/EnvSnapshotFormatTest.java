package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「固化环境」产出格式的契约测试。
 *
 * <p><b>测不了的那一半</b>：{@code EnvSnapshot.capture(NumenPlayer)} 在插件层、要 MC 类路径，
 * 单测跑不到。所以这里测的是<b>它产出格式的另一半</b>：
 * <b>那种格式的字符串，能不能被 {@link Memo} 真的解析出分级结论</b>。
 *
 * <p>为什么这个间接测试值得做：格式是<b>两份代码之间的契约</b>（采集器写 / {@code Memo} 读）。
 * 任何一边改了格式而另一边没跟上，症状是「采集明明跑了，但分级永远 UNKNOWN」——
 * 一个<b>不会报错、只会静默降级</b>的故障，正是最难查的那类。
 *
 * <p>契约三条（见 {@code EnvSnapshot} 类注释）：
 * ① 只按 {@code , ;} 切，<b>不能按空格切</b>（2026-09-29 修过的真 bug）
 * ② 值只含字母数字与 {@code _ . / -}
 * ③ 血量写成 {@code hp=n/max}，两种取法都能解析
 */
class EnvSnapshotFormatTest {

    /** 生效链是进程级静态（{@code Memo.assessCarrier} 会读它）：先刷回 DEFAULT 再断言。 */
    @BeforeEach
    void isolateCarrierStore() {
        CarrierStoreIsolation.installEmpty();
    }

    /** 一条按 capture() 的真实规则拼出来的快照（各项都取过值）。 */
    private static final String FULL =
            "hp=6/20, food=11, armor=iron_chestplate, chestplate=iron_chestplate, leggings=iron_leggings, "
                    + "helmet=iron_helmet, boots=iron_boots, weapon=iron_sword, "
                    + "nearby=zombie+creeper+pig, hostile=true, dim=overworld, pos=12,64,-30";

    /** 什么都没有时的样子：只有明确的无标记，键一个不少。 */
    private static final String BARE =
            "hp=20/20, food=20, armor=none, weapon=none, hostile=false, dim=overworld, pos=0,64,0";

    @Test
    void fullCaptureParsesIntoRealGrades() {
        Map<String, Object> sig = new Memo("m", "p", "s", "t", FULL, 1L).carrierSignal();
        assertEquals(Boolean.TRUE, sig.get("snapshot_present"));
        assertEquals(Boolean.TRUE, sig.get("snapshot_parsed"), "采集格式必须能被 parseKeyValues 认出来");
        assertEquals(6, sig.get("carrier_hp"), "hp=6/20 必须解析出 6");
        assertEquals("HOSTILE_NEARBY", sig.get("carrier_target"), "hostile=true 必须在");
    }

    @Test
    void bareCaptureStillCountsAsParsed() {
        // 「真的没护甲/没武器/附近没人」是**有效观测**，不能与「没读懂」混为一谈
        Map<String, Object> sig = new Memo("m", "p", "s", "t", BARE, 1L).carrierSignal();
        assertEquals(Boolean.TRUE, sig.get("snapshot_parsed"));
        assertEquals(20, sig.get("carrier_hp"));
        assertEquals("NONE", sig.get("carrier_target"), "hostile=false 且附近无实体 → NONE 是真值");
    }

    @Test
    void nearbyPlusSeparatedNamesStillHitHostileDetection() {
        // capture() 用 '+' 连接多个实体名（'+' 不在 cleanValue 的白名单里，会被剥掉），
        // 所以实体名之间不能靠 '+' 传递信息 —— 敌对判定必须靠 hostile=true 这个独立键。
        Map<String, Object> sig = new Memo("m", "p", "s", "t",
                "hp=6/20, food=11, armor=none, weapon=none, nearby=zombie+creeper, hostile=true, dim=nether",
                1L).carrierSignal();
        assertEquals("HOSTILE_NEARBY", sig.get("carrier_target"));
    }

    @Test
    void hostileFlagAloneIsEnoughWithoutNames() {
        // 实体名被剥掉/超限时，hostile=true 仍要让分级判成 HOSTILE_NEARBY
        Map<String, Object> sig = new Memo("m", "p", "s", "t",
                "hp=6/20, food=11, armor=none, weapon=none, hostile=true, dim=nether", 1L).carrierSignal();
        assertEquals("HOSTILE_NEARBY", sig.get("carrier_target"));
    }

    @Test
    void foodKeyIsCarriedEvenThoughCarrierDoesNotGradeOnIt() {
        // food 是为 D1「饿」留的信号。携带器当前不按它分级（B5 级数留白），
        // 但**不许因此把它从快照里去掉** —— 那是 D1 死因唯一的客观信号。
        String s = "hp=6/20, food=3, armor=none, weapon=none, hostile=false, dim=overworld";
        assertTrue(s.contains("food=3"));
        Map<String, Object> sig = new Memo("m", "p", "s", "t", s, 1L).carrierSignal();
        assertEquals(Boolean.TRUE, sig.get("snapshot_parsed"), "带 food 的串仍必须解析成功");
        assertFalse(sig.containsKey("carry_list_meaning"), "有快照时不该出现 UNKNOWN_NO_SNAPSHOT 标记");
    }

    @Test
    void posWithCommasDoesNotBreakKeyValueParsing() {
        // pos=12,64,-30 里有逗号 —— parseKeyValues 按 ',' 切，会把它切成三片。
        // 断言这**不会污染**前面已解析好的键（用 putIfAbsent，先到先得）。
        Map<String, Object> sig = new Memo("m", "p", "s", "t",
                "hp=6/20, food=11, armor=none, weapon=none, hostile=true, pos=12,64,-30", 1L).carrierSignal();
        assertEquals(6, sig.get("carrier_hp"), "逗号不得让前面的 hp 丢失");
        assertEquals("HOSTILE_NEARBY", sig.get("carrier_target"));
    }

    @Test
    void emptyCaptureStringIsTreatedAsMissingNotAsBadFormat() {
        // 同伴不可用时 capture() 返回空串 —— 那是「缺失」，走 B21 那一支
        Map<String, Object> sig = new Memo("m", "p", "s", "t", "", 1L).carrierSignal();
        assertEquals(Boolean.FALSE, sig.get("snapshot_present"));
        assertEquals("UNKNOWN_NO_SNAPSHOT", sig.get("carry_list_meaning"));
    }

    @Test
    void everyKeyCaptureEmitsIsRecognisedByTheParser() {
        // 逐个键单独试一遍：任何一个键单独出现时都不能让整串解析失败
        for (String kv : new String[] { "hp=6/20", "food=11", "armor=iron_chestplate", "chestplate=iron_chestplate",
                "leggings=iron_leggings", "helmet=iron_helmet", "boots=iron_boots", "weapon=iron_sword",
                "nearby=zombie", "hostile=true", "dim=nether", "pos=12,64,-30" }) {
            Map<String, Object> sig = new Memo("m", "p", "s", "t", kv, 1L).carrierSignal();
            assertEquals(Boolean.TRUE, sig.get("snapshot_parsed"),
                    "键 [" + kv + "] 单独出现时解析失败 → capture() 与 Memo 的格式契约已脱节");
        }
    }
}
