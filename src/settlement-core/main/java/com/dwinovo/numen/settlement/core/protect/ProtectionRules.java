package com.dwinovo.numen.settlement.core.protect;

import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.ProtectionZone;

import java.util.List;

/**
 * 位置级设施保护的裁决真源（纯函数）。
 *
 * <p>规则：
 * <ul>
 *   <li><b>STRUCTURE</b>（墙/围栏/地基/床/箱）：落在保护结构格上的任何改动一律拒绝——
 *       这是"禁止随意拆除或替换"。</li>
 *   <li><b>SPACE</b>（入口/通道）：只拒绝"往通道里放挡路方块"，拆开通道是允许的。</li>
 *   <li><b>作业区</b>不在 zones 里，不产生硬禁，耕种/收割/补种/维修天然放行。</li>
 * </ul>
 *
 * <p><b>授权优先</b>：命中 {@link Grant}（当前设施、当前作业范围）时直接放行。授权由施工/维修
 * 任务的生命周期持有，结束即撤销——所以"暂停、取消、结束后权限失效，持久保护继续存在"。
 *
 * <p>这个函数同时给两处用：规划路径的成本判定（找不到路就绕开）和改世界前的执行校验
 * （真的不让下手）。两处读同一份规则，才不会"规划时绕着走、执行时顺手拆了"。
 */
public final class ProtectionRules {

    private ProtectionRules() {}

    public static Decision decide(FacilityRegistry registry, String dimension,
                                  int x, int y, int z, Action action,
                                  String newBlockId, BlockClassifier classifier,
                                  List<Grant> grants, long nowEpochMs) {
        // 1) 临时授权：当前设施当前作业范围内，自己可以改自己。
        if (grants != null) {
            for (Grant grant : grants) {
                if (grant.covers(dimension, x, y, z, nowEpochMs)) return Decision.ALLOW;
            }
        }
        if (registry == null || dimension == null) return Decision.ALLOW;

        // 2) 持久保护：命中任一设施的保护区就按类别裁决。
        for (FacilityRecord facility : registry.inDimension(dimension)) {
            for (ProtectionZone zone : facility.zones()) {
                if (!zone.box().contains(x, y, z)) continue;
                switch (zone.kind()) {
                    case STRUCTURE -> {
                        return Decision.DENY;
                    }
                    case SPACE -> {
                        boolean placing = action == Action.PLACE || action == Action.REPLACE;
                        if (placing && classifier != null && classifier.obstructs(newBlockId)) {
                            return Decision.DENY;
                        }
                    }
                }
            }
        }
        return Decision.ALLOW;
    }

    public static Decision decide(FacilityRegistry registry, String dimension,
                                  int x, int y, int z, Action action,
                                  String newBlockId, BlockClassifier classifier) {
        return decide(registry, dimension, x, y, z, action, newBlockId, classifier, List.of(), 0L);
    }

    /** 拆方块专用：结构不可拆；通道里的方块可以拆。 */
    public static Decision decideBreak(FacilityRegistry registry, String dimension,
                                       int x, int y, int z, List<Grant> grants, long nowEpochMs) {
        return decide(registry, dimension, x, y, z, Action.BREAK, null,
                new SimpleBlockClassifier(), grants, nowEpochMs);
    }

    /** 放方块专用：通道/入口禁止填堵，作业区与空地放行。 */
    public static Decision decidePlace(FacilityRegistry registry, String dimension,
                                       int x, int y, int z, String blockId,
                                       BlockClassifier classifier, List<Grant> grants, long nowEpochMs) {
        return decide(registry, dimension, x, y, z, Action.PLACE, blockId, classifier, grants, nowEpochMs);
    }

    /**
     * 放方块专用（宿主已按真实碰撞箱判好"挡不挡路"）。
     *
     * <p>为什么另开一个：宿主（Minecraft 侧）能直接问方块碰撞箱，比我们在纯核心里列方块清单准。
     * 于是"挡路与否"由宿主算好、以布尔传进来，规则本体（STRUCTURE 全拒 / SPACE 只拒挡路）
     * 仍留在这一处，两边不会各写一份。
     */
    public static Decision decidePlaceObstruction(FacilityRegistry registry, String dimension,
                                                  int x, int y, int z, boolean newObstructs,
                                                  List<Grant> grants, long nowEpochMs) {
        if (grants != null) {
            for (Grant grant : grants) {
                if (grant.covers(dimension, x, y, z, nowEpochMs)) return Decision.ALLOW;
            }
        }
        if (registry == null || dimension == null) return Decision.ALLOW;
        for (FacilityRecord facility : registry.inDimension(dimension)) {
            for (ProtectionZone zone : facility.zones()) {
                if (!zone.box().contains(x, y, z)) continue;
                switch (zone.kind()) {
                    case STRUCTURE -> {
                        return Decision.DENY;
                    }
                    case SPACE -> {
                        if (newObstructs) return Decision.DENY;
                    }
                }
            }
        }
        return Decision.ALLOW;
    }
}
