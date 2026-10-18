package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B21「缺失的表达方式」的契约测试。
 *
 * <p><b>为什么要有这个类</b>：2026-10-01 第 0 步实机抓到 —— 快照缺失时
 * {@code learner_note} 上报 {@code carrier_hp=-1}，而 -1 在
 * {@link Memo.CarrierAssessment} 里的定义是「没抓到」的哨兵值。
 * 原样写进 jsonl 就<b>看起来像数据</b>；更糟的是 carryList 会出现
 * 「无额外携带需求」这种<b>凭空产生的结论</b>。下游分不出它和真判断。
 *
 * <p><b>本类把「不许泄漏哨兵值」钉成单测</b>，否则下次重构又会漏回去。
 * 因为契约下沉在 core（纯 JVM，不依赖 NUMEN/MC），所以这些断言跑得到 ——
 * 原先这段逻辑长在插件层的工具类里，单测根本够不着。
 */
class CarrierSignalB21Test {

    /** 生效链是进程级静态：每个用例先刷回 DEFAULT，否则读到别的测试类批准的规则。 */
    @BeforeEach
    void isolateCarrierStore() {
        CarrierStoreIsolation.installEmpty();
    }

    private static Memo memo(String snapshot) {
        return new Memo("m-1", "problem", "stage", "tried", snapshot, 1L);
    }

    // ---------- 缺失：必须「没有这个键」，不许出现 -1 / UNKNOWN 伪值 ----------

    @Test
    void missingSnapshotDoesNotLeakSentinelHp() {
        Map<String, Object> sig = memo("").carrierSignal();
        assertFalse(sig.containsKey("carrier_hp"),
                "缺快照时绝不能出现 carrier_hp（旧写法写 -1，那是哨兵不是数据），实际: " + sig);
        assertFalse(sig.containsKey("carrier_target"),
                "缺快照时绝不能出现 carrier_target（下游分不出「真判出 UNKNOWN」与「没快照可判」）");
    }

    @Test
    void missingSnapshotIsExplicitlyFlaggedNotGuessed() {
        Map<String, Object> sig = memo("").carrierSignal();
        assertEquals(Boolean.FALSE, sig.get("snapshot_present"), "必须显式告诉下游「没有快照」");
        assertEquals(0, sig.get("snapshot_chars"));
        assertEquals("UNKNOWN_NO_SNAPSHOT", sig.get("carry_list_meaning"));
        assertEquals(List.of(), sig.get("carry_list"),
                "缺快照时携带清单必须为空，不许出现「无额外携带需求」这种凭空结论");
    }

    @Test
    void missingSnapshotPreviewIsNullNotAPretendConclusion() {
        assertNull(memo("").carrierPreview(),
                "缺失时不给「可读依据」—— 依据本身就是结论，缺失不能伪装成结论");
        assertFalse(memo("").hasSnapshot());
    }

    @Test
    void nullSnapshotBehavesSameAsBlank() {
        Map<String, Object> sig = memo(null).carrierSignal();
        assertEquals(Boolean.FALSE, sig.get("snapshot_present"));
        assertEquals(0, sig.get("snapshot_chars"));
        assertFalse(sig.containsKey("carrier_hp"));
        assertNull(memo(null).carrierPreview());
    }

    @Test
    void whitespaceOnlySnapshotCountsAsMissing() {
        // 极端输入：全是空格。Java 的 isBlank() 对它为 true，所以语义上就是「没填」。
        // 这比「非空就算有」更安全 —— 空白不是数据，让它冒充数据才是 B21 要禁的行为。
        Map<String, Object> sig = memo("   ").carrierSignal();
        assertEquals(Boolean.FALSE, sig.get("snapshot_present"), "纯空白必须算「没有快照」");
        assertEquals(3, sig.get("snapshot_chars"), "但仍要如实记下调用方传了 3 个字符，便于诊断");
        assertEquals(Boolean.FALSE, sig.get("snapshot_parsed"));
        assertEquals("UNKNOWN_NO_SNAPSHOT", sig.get("carry_list_meaning"));
        assertFalse(sig.containsKey("carrier_hp"), "解析不出 hp 时不许写 -1");
    }

    @Test
    void unparseableButNonBlankSnapshotIsFlaggedPresentYetUngraded() {
        // 「有快照但认不出格式」是与「没快照」不同的第三种状态，必须能分开。
        // snapshot="garbage" 时 assessCarrier 判 target=NONE，而 NONE 是个真值
        //（「附近确实没有敌对实体」）。所以靠 snapshot_parsed 区分「没读懂」与「看过了，没有」。
        Map<String, Object> sig = memo("garbage").carrierSignal();
        assertEquals(Boolean.TRUE, sig.get("snapshot_present"), "非空即调用方声称有快照");
        assertEquals(Boolean.FALSE, sig.get("snapshot_parsed"), "但一个键都没解析出来");
        assertFalse(sig.containsKey("carrier_hp"), "认不出 hp 时不许写 -1");
    }

    @Test
    void validSnapshotWithNoHostilesIsParsedAndNone() {
        // 反向护栏：真正「看过、附近没敌对」必须 parsed=true + target=NONE，
        // 不能被 snapshot_parsed 一起打成 false（那就退化成「没读懂」了）
        Map<String, Object> sig = memo("hp=20/20, armor=iron_chestplate, dim=overworld").carrierSignal();
        assertEquals(Boolean.TRUE, sig.get("snapshot_parsed"));
        assertEquals("NONE", sig.get("carrier_target"));
        assertEquals(20, sig.get("carrier_hp"));
    }

    // ---------- 有快照：正常给分级结果 ----------

    @Test
    void realSnapshotYieldsRealSignals() {
        Map<String, Object> sig = memo("hp=6/20, armor=none, weapon=none, nearby=zombie, dim=overworld")
                .carrierSignal();
        assertEquals(Boolean.TRUE, sig.get("snapshot_present"));
        assertEquals(6, sig.get("carrier_hp"), "真值必须原样给出");
        assertEquals("HOSTILE_NEARBY", sig.get("carrier_target"));
        assertNotNull(sig.get("carry_list"));
    }

    @Test
    void highHpRealValueIsNotSwallowedBySentinelGuard() {
        // 回归护栏（本类抓到过真 bug）：>=0 的判断不能误伤 0 血量。
        // 旧 extractHp 用 isNegative() 挡输入，而 isNegative("0")==true → hp=0 被当成「没数据」。
        // 但 hp=0 是最关键的血量值（濒死/已死亡）—— 恰好在最该报警的时刻把数据丢了。
        Map<String, Object> sig = memo("hp=0, armor=none, weapon=none, nearby=zombie").carrierSignal();
        assertEquals(0, sig.get("carrier_hp"), "hp=0 是真值，必须出现（旧代码会漏成 null）");
        assertEquals("CRITICAL", memo("hp=0, armor=none, weapon=none, nearby=zombie")
                .assessCarrier().why().split("血量=")[1].split("\\(")[0],
                "hp=0 必须落到 CRITICAL 档");
    }

    @Test
    void absentTokensStillRejectedAsHp() {
        // 修 hp=0 不能放松成「什么都收」：none/无/unknown 这类仍必须是不写 carrier_hp
        for (String bad : new String[] { "hp=none", "hp=无", "hp=unknown", "hp=-", "hp=null" }) {
            Map<String, Object> sig = memo(bad + ", armor=none").carrierSignal();
            assertFalse(sig.containsKey("carrier_hp"),
                    "「没填值」的标记词必须仍被拒，输入=[" + bad + "]，实际=" + sig);
        }
    }

    @Test
    void negativeHpIsNotAcceptedAsData() {
        // hp=-3 这种脏输入：extractHp 只收数字前缀，-3 会解析失败 → 不写 carrier_hp
        Map<String, Object> sig = memo("hp=-3, armor=none").carrierSignal();
        assertFalse(sig.containsKey("carrier_hp"), "负数不是合法血量，不许原样上报");
    }

    @Test
    void snapshotCharsLetsDownstreamTellAbsentFromTooShort() {
        assertEquals(0, memo("").carrierSignal().get("snapshot_chars"));
        assertEquals(34, memo("hp=6/20, armor=none, nearby=zombie")
                .carrierSignal().get("snapshot_chars"));
    }

    @Test
    void signalNeverContainsTheBogusConclusionString() {
        // 兜底：无论什么输入，信号里都不许出现这句话
        for (String s : new String[] { "", "   ", "hp=6/20", "armor=none", "garbage" }) {
            String all = String.valueOf(memo(s).carrierSignal());
            assertFalse(all.contains("无额外携带需求"),
                    "信号里出现「无额外携带需求」= 凭空结论，输入=[" + s + "]");
        }
    }

    @Test
    void signalIsOrderedSoPresentFlagIsReadFirst() {
        // LinkedHashMap 的插入序 = 消费顺序：下游读日志时第一眼就该看到 snapshot_present
        List<String> keys = List.copyOf(memo("").carrierSignal().keySet());
        assertEquals("snapshot_present", keys.get(0));
        assertEquals("snapshot_chars", keys.get(1));
    }

    @Test
    void previewIsHumanReadableWhenSnapshotPresent() {
        String p = memo("hp=6/20, armor=none, nearby=zombie").carrierPreview();
        assertNotNull(p);
        assertTrue(p.contains("指向=HOSTILE_NEARBY"), p);
        assertTrue(p.contains("血量="), p);
    }
}
