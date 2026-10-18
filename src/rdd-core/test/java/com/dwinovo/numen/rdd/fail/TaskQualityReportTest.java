package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2-B 任务质量反馈（只读上报）：模型 + 缺项计算 + 渲染 + 事件序列化。 */
class TaskQualityReportTest {

    @Test void missingAssetsComputesGap() {
        var r = new TaskQualityReport(
                TaskQualityReport.Reason.ASSET_UNREACHABLE, "等小麦成熟", 3,
                Map.of("minecraft:wheat", 12), Map.of("minecraft:wheat", 5),
                "3 分钟", "改去村庄找食物");
        assertEquals(1, r.missingAssets().size());
        assertTrue(r.missingAssets().get(0).contains("minecraft:wheat"));
        assertTrue(r.missingAssets().get(0).contains("need 12"));
        assertTrue(r.missingAssets().get(0).contains("have 5"));
    }

    @Test void noGapWhenAvailableMeetsRequired() {
        var r = new TaskQualityReport(
                TaskQualityReport.Reason.OTHER, "做面包", 0,
                Map.of("minecraft:bread", 4), Map.of("minecraft:bread", 4),
                "", "");
        assertTrue(r.missingAssets().isEmpty());
    }

    @Test void renderIncludesReasonAndSuggestion() {
        var r = new TaskQualityReport(
                TaskQualityReport.Reason.RESOURCE_COST_TOO_HIGH, "种小麦", 2,
                Map.of(), Map.of(), "高时间成本", "跳过农业，寻找村庄");
        String s = r.render();
        assertTrue(s.contains("RESOURCE_COST_TOO_HIGH"));
        assertTrue(s.contains("种小麦"));
        assertTrue(s.contains("failed 2x"));
        assertTrue(s.contains("跳过农业，寻找村庄"));
        assertTrue(r.hasSuggestion());
    }

    @Test void eventDataIsFlatAndComplete() {
        var r = new TaskQualityReport(
                TaskQualityReport.Reason.MISALIGNED_WITH_GOAL, "补种小麦", 1,
                Map.of("minecraft:wheat", 12), Map.of("minecraft:bread", 20),
                "", "主线已是下界");
        Map<String, Object> d = r.toEventData("uuid-1");
        assertEquals("uuid-1", d.get("companionId"));
        assertEquals("MISALIGNED_WITH_GOAL", d.get("reason"));
        assertEquals("补种小麦", d.get("currentTask"));
        assertEquals(1, d.get("failedAttempts"));
        assertNotNull(d.get("summary"));
        assertTrue(d.get("requiredAssets") instanceof Map);
        assertTrue(d.get("availableAssets") instanceof Map);
    }

    @Test void nullReasonIsRejected() {
        assertThrows(NullPointerException.class, () -> new TaskQualityReport(
                null, "t", 0, Map.of(), Map.of(), "", ""));
    }

    @Test void nullTextFieldsNormalizeToEmpty() {
        var r = new TaskQualityReport(
                TaskQualityReport.Reason.OTHER, null, 0, null, null, null, null);
        assertEquals("", r.currentTask());
        assertTrue(r.requiredAssets().isEmpty());
        assertTrue(r.availableAssets().isEmpty());
        assertFalse(r.hasSuggestion());
    }

    @Test void failedAttemptsClampedNonNegative() {
        var r = new TaskQualityReport(
                TaskQualityReport.Reason.OTHER, "t", -3, Map.of(), Map.of(), "", "");
        assertEquals(0, r.failedAttempts());
    }
}
