package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 元件检测 · 一级目标资产要求清单（RequirementManifest）。
 *
 * <p>【用户架构概念 2/4】一级大目标生成时<b>同步产出</b>该目标所需的物品/环境条件；
 * 系统持续粗粒度检测（20~30s）"需求是否满足"，满足则交 Supervisor 判定。
 * <b>核心：检测 != 重规划</b>——检测层只产事实，Supervisor 决定事实有无规划意义。
 *
 * <p>本类是<b>需求侧</b>的纯数据 + 纯函数：只回答"这个目标还需要什么 / 现在满足了吗"，
 * 不碰任务链、不触 MC、不决定是否重规划（那是 Supervisor）。可单测。
 *
 * <p>替代规则：如 {@code any_log -> planks}（同类可替代），由 {@link Requirement#alternatives()} 承载。
 */
public final class RequirementManifest {

    private RequirementManifest() {}

    /** 单条需求：主资产键 + 最低数量 + 可替代键（任一满足即可）。 */
    public record Requirement(String key, int minimum, List<String> alternatives) {
        public Requirement {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("requirement key required");
            if (minimum < 0) throw new IllegalArgumentException("minimum must be >= 0");
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        /** 该需求在给定持有计数下是否满足（主键或任一替代达标）。 */
        public boolean satisfiedBy(Map<String, Integer> held) {
            int have = held == null ? 0 : held.getOrDefault(key, 0);
            if (have >= minimum) return true;
            for (String alt : alternatives) {
                if (held != null && held.getOrDefault(alt, 0) >= minimum) return true;
            }
            return false;
        }

        /** 缺口的人类可读转写（满足则空串）。 */
        public String gapText(Map<String, Integer> held) {
            if (satisfiedBy(held)) return "";
            int have = held == null ? 0 : held.getOrDefault(key, 0);
            return key + " 需要 " + minimum + " 现有 " + have
                    + (alternatives.isEmpty() ? "" : "（或 " + String.join("/", alternatives) + "）");
        }
    }

    /**
     * 目标需求清单：由一级目标生成时同步产出（首批以资产需求为主；环境条件第二批扩展）。
     */
    public record Manifest(String goalId, List<Requirement> requirements) {
        public Manifest {
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
        }

        /** 全部满足 = 该目标的需求已达成（可交 Supervisor 判定 MARK_SATISFIED）。 */
        public boolean allSatisfied(Map<String, Integer> held) {
            for (Requirement r : requirements) {
                if (!r.satisfiedBy(held)) return false;
            }
            return true;
        }

        /** 未满足的缺口列表（空 = 满足）。 */
        public List<String> gaps(Map<String, Integer> held) {
            List<String> out = new ArrayList<>();
            for (Requirement r : requirements) {
                String g = r.gapText(held);
                if (!g.isEmpty()) out.add(g);
            }
            return out;
        }

        /**
         * 粗粒度检测判定：给出<b>事实</b>（满足/缺口），不决定是否重规划。
         * 与 {@link #allSatisfied} 同源；此处显式命名以便 Supervisor 语义对齐。
         */
        public Detection detect(Map<String, Integer> held) {
            List<String> gaps = gaps(held);
            return new Detection(goalId, gaps.isEmpty(), gaps);
        }
    }

    /** 检测结果（事实，非决策）。{@code satisfied=false} 时 {@code gaps} 非空。 */
    public record Detection(String goalId, boolean satisfied, List<String> gaps) {}

    /** 便捷构造：单需求清单。 */
    public static Manifest of(String goalId, String key, int minimum) {
        return new Manifest(goalId, List.of(new Requirement(key, minimum, List.of())));
    }
}
