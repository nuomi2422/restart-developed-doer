package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-A 风险判定收紧（验收3/4）：只在「明确的行动短语」上判高危，
 * 不因阶段描述里**提到**某高风险词就误升级（早期据点阶段曾被误挂 NETHER 硬门 → 死锁）。
 */
class RiskLevelTighteningTest {

    /** 验收3：普通食物/据点任务不得被判成 NETHER/END。 */
    @Test void ordinaryStageIsNotHighRisk() {
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("主世界据点与食物供应：床边放箱子、工作台、熔炉"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("获取食物与备用装备"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("为后续任务提前规划位置与回退路线"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("在村庄附近建立据点"));
    }

    /** 2026-09-25 二次收紧：含"备用装备/回退路线"的早期阶段绝不能被"备"字误判为高危。 */
    @Test void wordingWithAmbiguousCharsIsNotHighRisk() {
        assertEquals(RiskLevel.NORMAL,
                RiskGate.levelForText("对照当前状态盘点，核对基地设施与备用装备，规划回退路线"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("准备食物与背包整理（备用装备）"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("去打猎取肉，顺路捡树枝"));
    }

    /** 验收4：真正的下界/末地准备才判高危。 */
    @Test void realHighRiskStagesAreDetected() {
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("进入下界"));
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("前往下界采集烈焰棒"));
        assertEquals(RiskLevel.END, RiskGate.levelForText("击杀末影龙"));
        assertEquals(RiskLevel.END, RiskGate.levelForText("进入末地"));
        assertEquals(RiskLevel.MINING, RiskGate.levelForText("下矿采集钻石"));
    }

    /** 提到但非行动目的的引用（如"为下界做准备"的准备阶段）——保守仍判 NETHER（准备阶段本就该备）。 */
    @Test void preparationStageForNetherIsHighRisk() {
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("为进入下界做准备：备抗火药与装备"));
    }
}
