package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.fail.TaskQualityReport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P2-B 任务质量只读上报：把"这个任务值不值得做 / 做不做得到"的判断落成 monitor 事件，
 * 供 Supervisor/人 重新决策。<b>单向反馈，不做双向对话</b>（人工裁决）。
 *
 * <p>触发点：二级反复失败/资源耗尽停车时（{@code RddDetector.handleSubtaskFailure}）。只上报、
 * 绝不改任务链状态、绝不投 MCP 指令。
 */
final class RddTaskQualityReporter {

    private RddTaskQualityReporter() {}

    /** 事件类型：Supervisor/监测台按此过滤。 */
    static final String EVENT_TYPE = "task_quality_warning";

    /**
     * 上报一条任务质量警告（只读，不改链）。
     *
     * @param taskQualityReason 归一化类别（调用方给）
     * @param suggestedAction   建议动作（人话，可空）
     */
    static void report(NumenPlayer ap, Subtask current, TaskQualityReport.Reason taskQualityReason,
                       int failedAttempts, String estimatedCost, String suggestedAction) {
        if (ap == null || current == null) return;
        try {
            Map<String, Integer> required = requiredOf(current);
            Map<String, Integer> available = RddDetector.countInventory(ap);
            TaskQualityReport report = new TaskQualityReport(
                    taskQualityReason, current.description(), failedAttempts,
                    required, available, estimatedCost, suggestedAction);
            RddMonitor.publish(EVENT_TYPE, report.toEventData(ap.getUUID().toString()));
        } catch (RuntimeException ex) {
            // 上报失败绝不影响检测主流程
            org.slf4j.LoggerFactory.getLogger(RddTaskQualityReporter.class)
                    .warn("[rdd] 任务质量上报失败 {}: {}", current.id(), ex.toString());
        }
    }

    /** 从二级条件抽出必需资产（asset_key→minimum；group 不展开，记为 group:<name>）。 */
    private static Map<String, Integer> requiredOf(Subtask current) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Object key = current.condition().get("asset_key");
        Object group = current.condition().get("group");
        Object min = current.condition().get("minimum");
        int minimum = min instanceof Number n ? n.intValue() : 1;
        if (key instanceof String s && !s.isBlank()) {
            out.put(s, minimum);
        } else if (group instanceof String g && !g.isBlank()) {
            out.put("group:" + g, minimum);
        }
        return out;
    }
}
