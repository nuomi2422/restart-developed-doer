package com.dwinovo.numen.rdd.fail;

/**
 * 失败诊断器（P2.0，纯函数）：**代码先硬分类出口**，AI 只在 REPAIR/REPLAN 里出方案。
 *
 * <p>定位：给现有休眠的 REPLANNING 出口接上"活触发器"——以前 REPLAN 只是状态、没有产生它的事件；
 * 本层把 {@link FailureEvent}+{@link FailureContext} 变成 {@link RecoveryDecision}。
 *
 * <p>规则（保守、可回归、无隐藏推断）：
 * <ul>
 *   <li>SOFTWARE_DEFECT → SELF_COMPILE</li>
 *   <li>TARGET_LOST → REPLAN</li>
 *   <li>DEATH：有备用装备+有基地坐标 → RECOVER；否则 目标不可回收→REPLAN，否则 REPAIR</li>
 *   <li>RESOURCE_MISSING：目标不可回收→REPLAN，否则 REPAIR</li>
 *   <li>PATH_BLOCKED / TOOL_ERROR → REPAIR（换路线/修调用后重试）</li>
 *   <li>UNKNOWN：重复失败→REPLAN，否则 REPAIR（未知保持未知，不猜成缺工具）</li>
 * </ul>
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    public static RecoveryDecision classify(FailureEvent event, FailureContext context) {
        if (event == null) {
            throw new IllegalArgumentException("failure event required");
        }
        FailureContext ctx = context == null
                ? new FailureContext(false, false, false, false) : context;
        return switch (event.kind()) {
            case SOFTWARE_DEFECT -> new RecoveryDecision(RecoveryOutcome.SELF_COMPILE,
                    "确定性代码缺陷，走自变异流水线", false);
            case TARGET_LOST -> new RecoveryDecision(RecoveryOutcome.REPLAN,
                    "目标已永久失去，必须重规划", false);
            case DEATH -> {
                if (ctx.hasBackupEquipment() && ctx.hasBaseAndCoords()) {
                    yield new RecoveryDecision(RecoveryOutcome.RECOVER,
                            "死亡但有备用装备与基地坐标：回基地取装备并恢复，不重规划", true);
                }
                yield ctx.targetUnrecoverable()
                        ? new RecoveryDecision(RecoveryOutcome.REPLAN,
                                "死亡且目标/资源不可回收，重规划", false)
                        : new RecoveryDecision(RecoveryOutcome.REPAIR,
                                "死亡但缺备用/基地信息：补准备后继续", false);
            }
            case RESOURCE_MISSING -> ctx.targetUnrecoverable()
                    ? new RecoveryDecision(RecoveryOutcome.REPLAN, "资源不可获得，重规划", false)
                    : new RecoveryDecision(RecoveryOutcome.REPAIR, "缺资源：补获取后继续", false);
            case PATH_BLOCKED -> new RecoveryDecision(RecoveryOutcome.REPAIR,
                    "路径受阻：换路线/分层推进后继续", false);
            case TOOL_ERROR -> new RecoveryDecision(RecoveryOutcome.REPAIR,
                    "工具调用/参数问题：修正后重试", false);
            case UNKNOWN -> ctx.repeatedFailure()
                    ? new RecoveryDecision(RecoveryOutcome.REPLAN, "未知原因且重复失败：重规划", false)
                    : new RecoveryDecision(RecoveryOutcome.REPAIR, "未知原因：先保守补救", false);
        };
    }
}
