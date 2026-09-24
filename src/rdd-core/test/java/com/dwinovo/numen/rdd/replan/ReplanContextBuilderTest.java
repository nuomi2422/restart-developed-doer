package com.dwinovo.numen.rdd.replan;

import com.dwinovo.numen.rdd.fail.FailureEvent;
import com.dwinovo.numen.rdd.fail.FailureKind;
import com.dwinovo.numen.rdd.policy.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2.4 重规划上下文：真实状态汇总 + 渲染约束。 */
class ReplanContextBuilderTest {

    @Test void renderIncludesFailureAssetsCompletedAndRisk() {
        var ctx = ReplanContextBuilder.build(
                "通关MC", "primary-1", "s7",
                FailureEvent.of("s7", "primary-1", FailureKind.RESOURCE_MISSING, "缺铁"),
                List.of("起步石器", "铁器准备"),
                Map.of("minecraft:iron_ingot", 0, "minecraft:cobblestone", 24),
                RiskLevel.NETHER, List.of("minecraft:diamond_chestplate need 2 have 0"),
                List.of("下界需两套钻石甲"));
        String s = ReplanContextBuilder.render(ctx);
        assertTrue(s.contains("重规划上下文"));
        assertTrue(s.contains("RESOURCE_MISSING"));
        assertTrue(s.contains("缺铁"));
        assertTrue(s.contains("起步石器"));
        assertTrue(s.contains("minecraft:cobblestone×24"));
        assertTrue(s.contains("diamond_chestplate need 2"));
        assertTrue(s.contains("两套钻石甲"));
        assertTrue(s.contains("已完成的不重做"));
    }

    @Test void renderEmptyContextIsSafe() {
        var ctx = ReplanContextBuilder.build("目标", null, null, null,
                List.of(), Map.of(), RiskLevel.NORMAL, List.of(), List.of());
        String s = ReplanContextBuilder.render(ctx);
        assertTrue(s.contains("风险级别：NORMAL（已达标）"));
        assertTrue(s.contains("（空）"));
        assertFalse(s.contains("失败："));
    }
}
