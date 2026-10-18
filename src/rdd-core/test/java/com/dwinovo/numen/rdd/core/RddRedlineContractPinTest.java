package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ★ 红线值钉死点（rdd-core 侧）—— 配套 {@code plugins/rdd} 的 {@code RedlineContractPinTest}。
 *
 * <h2>为什么要有（2026-10-01 变异测试实测）</h2>
 *
 * <p>本模块原有 8 处「用被测常量当期望值」的自指断言，形如：
 *
 * <pre>
 *   assertEquals(RddChainFactory.MAX_OBJECTIVE_CHARS, goal.description().length());
 *   assertEquals(RddChainFactory.MAX_SUBTASKS, specs.size());
 *   assertEquals(RddChainFactory.MAX_STAGES,      stages.size());
 *   assertFalse(d.stillRecoverable(1000L + RddDeathLedger.DROPS_LIVE_TICKS));
 *   assertEquals(RddDeathLedger.MAX_ENTRIES,      RddDeathLedger.deathsRecorded(id));
 * </pre>
 *
 * <p>这类断言<b>无法失败</b>：期望值和被测值是同一个常量，改常量时两边一起变。
 * 更隐蔽的是<b>循环上界也用同一个常量</b>——
 * {@code for (int i = 0; i < MAX_ENTRIES * 3; i++)} 意味着把上限从 8 改成 64，
 * 循环就从灌 24 条变成灌 192 条，「封顶到 64」照样成立，
 * <b>连「上限到底是多少」这个信息在测试里都消失了</b>。
 *
 * <h2>本类干什么</h2>
 *
 * <p>把「这些上限应该是多少」变成被测对象，让常量改动必须经过 conscious 确认。
 * 行为正确性另有各类的功能测试负责；本类只管值。
 *
 * <p><b>对应红线</b>（见 {@code 30-主要功能不回退清单.md}）：
 * <ul>
 *   <li>{@code MAX_OBJECTIVE_CHARS / MAX_SUBTASKS / MAX_STAGES} → **RL-13 工具契约不做假承诺**、
 *       **RL-14 派活 subtask 逐字遵循**、**RL-18 第一个二级不得自身不可达**（都出在「规划产出的可执行性」）</li>
 *   <li>{@code DROPS_LIVE_TICKS} → **RL-6 掉落回收（死亡不丢账）** + **RL-16**。
 *       它是「5 分钟掉落窗口」的事实来源，直接决定回收窗口有多长</li>
 *   <li>{@code MAX_ENTRIES} → 台账有界，防止死亡循环把载荷撑爆（RL-6 的载荷面）</li>
 * </ul>
 */
class RddRedlineContractPinTest {

    // ── 规划产出的可执行性上限（RL-13 / RL-14 / RL-18）─────────────

    @Test
    void objectiveLengthCap_is4000() {
        assertEquals(4000, RddChainFactory.MAX_OBJECTIVE_CHARS,
                "目标描述长度上限契约是 4000 字符（超出即截断）。要改先改这里");
    }

    @Test
    void subtaskCountCap_is8() {
        assertEquals(8, RddChainFactory.MAX_SUBTASKS,
                "二级任务条数上限契约是 8。要改先改这里，并同步 RddDecomposerParseTest.capsAtMaxSubtasks 的字面量");
    }

    @Test
    void stageCountCap_is12() {
        assertEquals(12, RddChainFactory.MAX_STAGES,
                "一级阶段条数上限契约是 12。要改先改这里，并同步 RddStagePlannerParseTest.capsAtMaxStages 的字面量");
    }

    /** 护栏：一级必须比二级留有余量，否则每个一级都顶格二级等于没分级。 */
    @Test
    void stageCap_staysAboveSubtaskCap() {
        assertEquals(true, RddChainFactory.MAX_STAGES > RddChainFactory.MAX_SUBTASKS,
                "一级上限(" + RddChainFactory.MAX_STAGES + ") 必须大于二级上限("
                        + RddChainFactory.MAX_SUBTASKS + ")，否则分级形同虚设");
    }

    // ── RL-6 掉落回收窗口 ────────────────────────────────────────

    @Test
    void dropLiveWindow_isFiveMinutes() {
        // 契约：掉落存活 5 分钟 = 20 tick × 60 秒 × 5 = 6000 tick
        assertEquals(20L * 60L * 5L, RddDeathLedger.DROPS_LIVE_TICKS,
                "掉落存活窗口契约是 5 分钟 = 6000 tick（与原版 despawn 同口径）。"
                        + "要改先改这里，并同步 RddDeathLedgerTest 的字面量");
    }

    @Test
    void dropLiveWindow_isNotAccidentallySecondsOrMinutes() {
        // 专门防「有人把 tick 当秒」这类量纲错误：20 或 300 都是常见的错值
        assertEquals(false, RddDeathLedger.DROPS_LIVE_TICKS == 20L,
                "这看着像 20 tick（1 秒）—— 量纲错了，DROPS_LIVE_TICKS 的单位是 tick 不是秒");
        assertEquals(false, RddDeathLedger.DROPS_LIVE_TICKS == 300L,
                "这看着像 300 tick（15 秒）—— 量纲错了，契约是 6000 tick = 5 分钟");
    }

    // ── RL-6 台账有界 ────────────────────────────────────────────

    @Test
    void ledgerEntryCap_is8() {
        assertEquals(8, RddDeathLedger.MAX_ENTRIES,
                "死亡台账每同伴保留条数契约是 8（防死亡循环把载荷撑爆）。"
                        + "要改先改这里，并同步 RddDeathLedgerTest / RddDeathLedgerBatchTest 的字面量");
    }

    /** 护栏：上限必须远小于「一次死亡循环的合理次数」，否则有界保护形同虚设。 */
    @Test
    void ledgerEntryCap_isSmallEnoughToBite() {
        assertEquals(true, RddDeathLedger.MAX_ENTRIES <= 16,
                "台账上限(" + RddDeathLedger.MAX_ENTRIES + ") 已经放宽到 16 以上——"
                        + "死亡循环场景下 16 条就足以把恢复指令撑到不可用，有界保护名存实亡");
    }
}