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

    /**
     * 由阶段主题/文本判定风险级别。
     *
     * <p><b>P2-A 收紧</b>：不再「文本包含某词即高危」——早期据点阶段曾因描述里**提到**高风险词
     * 被误判成 NETHER，`injectWaitFor` 于是给它挂了钻石甲/抗火药硬门 → 永久 WAITING 死锁。
     * 现在只在**明确的行动短语**上判高危（如「进入下界」「击杀末影龙」「下矿采集」），
     * 或**准备类短语**（如「下界准备/备抗火」）——那本就是该备装的阶段。认不出=NORMAL（保守）。
     */
    public static RiskLevel levelForText(String text) {
        if (text == null || text.isBlank()) {
            return RiskLevel.NORMAL;
        }
        String t = text.toLowerCase();
        if (actionFor(t, "末地", "末影龙", "the_end")) {
            return RiskLevel.END;
        }
        if (actionFor(t, "下界", "地狱", "nether")) {
            return RiskLevel.NETHER;
        }
        if (actionFor(t, "下矿", "挖矿", "洞穴", "废弃矿井")) {
            return RiskLevel.MINING;
        }
        return RiskLevel.NORMAL;
    }

    /**
     * 是否出现"行动/准备"短语：目标词出现在**明确的多字行动词**附近（同一短窗口内）。
     *
     * <p><b>2026-09-25 二次收紧</b>：动词表**不得**含单字/歧义词——`备`（备用/设备/背包）、
     * `去`（过去/上去）、`打`（打听/打扮）会把"……备用装备的回退路线"误判成下界行动。
     * 只保留语义明确的多字动词。
     */
    private static boolean actionFor(String text, String... targets) {
        String[] verbs = {"进入", "前往", "击杀", "采集", "探索", "攻略", "挑战", "进军",
                "下矿", "挖矿", "筹备", "准备进入", "预备"};
        for (String target : targets) {
            int idx = text.indexOf(target);
            while (idx >= 0) {
                int from = Math.max(0, idx - 4);
                int to = Math.min(text.length(), idx + target.length() + 4);
                String window = text.substring(from, to);
                for (String v : verbs) {
                    if (window.contains(v)) return true;
                }
                idx = text.indexOf(target, idx + 1);
            }
        }
        return false;
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

    /**
     * P2.2+ 高风险准入（§6③）：在最低储备之上，额外要求"恢复点"证据——即已绑定的床/重生锚点。
     *
     * <p>依据我们自己的发现：床边复活锚点（床）是高风险活动（下界/末地/深矿）的**恢复能力**核心。
     * 没有恢复点就进高风险维度，一旦死亡即"从零"，正是要避免的。
     *
     * @param hasRecoveryPoint 是否已绑定床/重生锚点（由宿主查同伴 respawn 判定）
     * @return 与 {@link #check} 同构的裁决；未绑恢复点则 allowed=false 并追加准备任务
     */
    public static Verdict checkWithRecovery(RiskLevel level, Map<String, Integer> available,
                                            boolean hasRecoveryPoint) {
        RiskLevel lv = level == null ? RiskLevel.NORMAL : level;
        Verdict base = check(lv, available);
        // 仅对高风险级别（下界/末地）要求恢复点；NORMAL/MINING 不强制。
        boolean risky = lv == RiskLevel.NETHER || lv == RiskLevel.END;
        if (!risky || hasRecoveryPoint) {
            return base;
        }
        List<String> missing = new ArrayList<>(base.missing());
        missing.add("recovery_point need 1 have 0（未绑定床/重生锚点）");
        List<String> prep = new ArrayList<>(base.prepTasks());
        prep.add("先找床绑定重生点（interact_at 右键床即绑），再进" + lv.name().toLowerCase() + "；否则死亡即从零");
        return new Verdict(false, lv, List.copyOf(missing), List.copyOf(prep));
    }

    /** 供测试/宿主使用的已知风险关键词集合（保持与 {@link #levelForText} 一致）。 */
    public static Set<String> knownRiskKeywords() {
        return Set.of("下界", "地狱", "nether", "末地", "末影龙", "the_end", "下矿", "挖矿", "洞穴", "废弃矿井");
    }
}
