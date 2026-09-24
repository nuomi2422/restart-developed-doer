package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.policy.ResourceBudget;
import com.dwinovo.numen.rdd.policy.RiskGate;
import com.dwinovo.numen.rdd.policy.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划期风险前置（P2.2 接线，主人定：**在规划时就意识到危险、提前备装**，不是等到进门才拦）。
 *
 * <p>两件事：
 * <ul>
 *   <li>{@link #prepHint}：把该目标/阶段的风险级别与最低储备写进规划提示词，逼规划师**提前**安排准备步骤。</li>
 *   <li>{@link #injectWaitFor}：给 Stage-A 的**高风险阶段**自动补 wait_for 硬门（来自 ResourceBudget），
 *       让“没备齐就不放行该阶段”由代码执行，而不是口头提醒。</li>
 * </ul>
 * 纯逻辑（只用 rdd-core 的 RiskGate/ResourceBudget），可单测；不碰 Minecraft。
 */
final class RddRiskPlanning {

    private RddRiskPlanning() {}

    /** 生成“风险前置”提示词块；NORMAL 返回空串（不打扰普通规划）。 */
    static String prepHint(String text, Map<String, Integer> available) {
        RiskLevel level = RiskGate.levelForText(text);
        if (level == RiskLevel.NORMAL) {
            return "";
        }
        Map<String, Integer> required = ResourceBudget.requiredFor(level);
        if (required.isEmpty()) {
            return "";
        }
        List<String> missing = ResourceBudget.missingFor(level, available);
        StringBuilder sb = new StringBuilder();
        sb.append("【风险前置｜").append(levelLabel(level)).append("】本目标/阶段属高风险。进入前必须备齐（真实资产）：\n");
        for (Map.Entry<String, Integer> e : required.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" ×").append(e.getValue()).append("\n");
        }
        if (missing.isEmpty()) {
            sb.append("当前缺口：无（已达标，保持不重复采集）。\n");
        } else {
            sb.append("当前缺口：").append(String.join("; ", missing)).append("\n");
        }
        sb.append("请把这些准备安排在该风险阶段**之前**的独立阶段（或该阶段开头）**提前完成**，"
                + "不要等到了现场才临时准备；下界/末地还需临时据点/回退路线与相应药水。\n\n");
        return sb.toString();
    }

    /**
     * 给高风险阶段自动补 wait_for 硬门：合并 ResourceBudget 的要求到已有 waitFor（同 key 取较大 minimum）。
     * NORMAL 阶段原样返回。
     */
    static List<PrimarySpec> injectWaitFor(List<PrimarySpec> stages, Map<String, Integer> available) {
        if (stages == null || stages.isEmpty()) {
            return stages == null ? List.of() : stages;
        }
        List<PrimarySpec> out = new ArrayList<>(stages.size());
        for (PrimarySpec stage : stages) {
            if (stage == null) {
                continue;
            }
            RiskLevel level = RiskGate.levelForText(stage.description());
            Map<String, Integer> required = ResourceBudget.requiredFor(level);
            if (level == RiskLevel.NORMAL || required.isEmpty()) {
                out.add(stage);
                continue;
            }
            Map<String, Integer> merged = new LinkedHashMap<>();
            for (AssetRequirement r : stage.waitFor()) {
                merged.put(r.assetKey(), r.minimum());
            }
            for (Map.Entry<String, Integer> e : required.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), Math::max);
            }
            List<AssetRequirement> wf = new ArrayList<>();
            for (Map.Entry<String, Integer> e : merged.entrySet()) {
                wf.add(new AssetRequirement(e.getKey(), e.getValue()));
            }
            out.add(new PrimarySpec(stage.description(), wf));
        }
        return List.copyOf(out);
    }

    private static String levelLabel(RiskLevel level) {
        return switch (level) {
            case NORMAL -> "常规";
            case MINING -> "下矿/洞穴";
            case NETHER -> "下界";
            case END -> "末地";
        };
    }
}
