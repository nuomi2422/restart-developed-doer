package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 风险门（P2.2，纯函数）：进入高风险活动前，用**代码硬规则**判“允许 / 阻止 + 缺什么 + 建议准备任务”。
 *
 * <p>不是规划器：只回答允许与否与缺口。规划/追加任务由宿主据本裁决执行——把“不能违反的生存规则”
 * 落在代码里，不靠 LLM 记规则。（GPT/主人对齐：RiskGate + Requirement 系统一起推进。）
 *
 * <p>依赖 {@link ResourceBudget}（最低储备）与真实可用资产计数（通常来自
 * {@code PlanningAssetSnapshot.availableCounts()}）。纯 JVM、可单测。
 */
public final class RiskGate {

    /**
     * @param allowed   是否允许进入
     * @param level     判定的风险级别
     * @param missing   "key need X have Y" 缺口列表（空=达标）
     * @param prepTasks 建议的准备任务描述（缺口的人类可读转写）
     */
    public record Verdict(boolean allowed, RiskLevel level, List<String> missing, List<String> prepTasks) {}

    private RiskGate() {}

    /** 按风险级别检查最低储备。 */
    public static Verdict check(RiskLevel level, Map<String, Integer> available) {
        RiskLevel lv = level == null ? RiskLevel.NORMAL : level;
        List<String> missing = ResourceBudget.missingFor(lv, available);
        if (missing.isEmpty()) {
            return new Verdict(true, lv, List.of(), List.of());
        }
        return new Verdict(false, lv, missing, prepare(lv, missing));
    }

    /** 由维度 ID 判定风险级别（如 minecraft:the_nether → NETHER）。 */
    public static RiskLevel levelForDimension(String dimensionId) {
        if (dimensionId == null) {
            return RiskLevel.NORMAL;
        }
        String d = dimensionId.toLowerCase();
        if (d.contains("the_nether") || d.contains("nether")) {
            return RiskLevel.NETHER;
        }
        if (d.contains("the_end") || d.endsWith(":end")) {
            return RiskLevel.END;
        }
        return RiskLevel.NORMAL;
    }

    /** 由阶段主题/文本判定风险级别（关键词，保守：认不出=NORMAL）。 */
    public static RiskLevel levelForText(String text) {
        if (text == null || text.isBlank()) {
            return RiskLevel.NORMAL;
        }
        String t = text.toLowerCase();
        if (t.contains("末地") || t.contains("末影龙") || t.contains("the_end")) {
            return RiskLevel.END;
        }
        if (t.contains("下界") || t.contains("地狱") || t.contains("nether")) {
            return RiskLevel.NETHER;
        }
        if (t.contains("下矿") || t.contains("挖矿") || t.contains("洞穴") || t.contains("废弃矿井")) {
            return RiskLevel.MINING;
        }
        return RiskLevel.NORMAL;
    }

    /**
     * 把缺口转成准备任务描述。核心策略：缺 N 件 → “获取/制作至 N”；抗火/治疗归“酿造”；
     * 下界额外提醒第二套甲与临时据点。只给方向，具体拆解仍交 Planner。
     */
    private static List<String> prepare(RiskLevel level, List<String> missing) {
        List<String> tasks = new ArrayList<>();
        for (String m : missing) {
            String key = m.split(" ")[0];
            String shortName = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
            if (shortName.contains("potion")) {
                tasks.add("酿造 " + shortName + "（缺：" + m + "）");
            } else if (shortName.startsWith("diamond_")) {
                tasks.add("制作/获取钻石装备 " + shortName + "（缺：" + m + "，下界需两套）");
            } else {
                tasks.add("获取 " + shortName + "（缺：" + m + "）");
            }
        }
        if (level == RiskLevel.NETHER) {
            tasks.add("下界前：确认临时据点/回退路线与备用装备");
        } else if (level == RiskLevel.END) {
            tasks.add("末地前：确认回退物资、床与末影之眼、要塞传送门");
        }
        return List.copyOf(tasks);
    }

    /** 便捷：该级别是否达标。 */
    public static boolean allows(RiskLevel level, Map<String, Integer> available) {
        return check(level, available).allowed();
    }

    /** 供测试/宿主使用的已知风险关键词集合（保持与 {@link #levelForText} 一致）。 */
    public static Set<String> knownRiskKeywords() {
        return Set.of("下界", "地狱", "nether", "末地", "末影龙", "the_end", "下矿", "挖矿", "洞穴", "废弃矿井");
    }
}
