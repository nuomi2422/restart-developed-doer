package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.ConstructionStatus;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityRegistry;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 放置请求的去重与续建判定（纯函数）——用户点名的第 ④ 条。
 *
 * <p>两条硬要求：
 * <ol>
 *   <li><b>重复调用必须识别已有任务或设施</b>：同一个放置请求不能生成两座同名建筑、
 *       不能重复开授权。</li>
 *   <li><b>续建要沿用原来的模板版本、锚点和方向</b>：不能重新解算坐标（基地原点或
 *       格边长变了就会建到别处）。</li>
 * </ol>
 *
 * <p>判定只读登记账，不看世界——世界探测是另一层（"这块地现在有没有东西"），
 * 两者分开才不会把"账上没有"当成"地上没有"。
 */
public final class PlacementLedger {

    private PlacementLedger() {}

    /** 一次放置请求的处置方式。 */
    public enum Disposition {
        /** 全新放置：可以开工。 */
        NEW,
        /** 已有同名设施在施工中 → 续建（沿用原模板/锚点/朝向）。 */
        RESUME,
        /** 已有同名设施已收口 → 重复请求，拒绝（不建第二座）。 */
        ALREADY_COMPLETE,
        /** 已有同名设施施工失败 → 需要显式 resume 才继续。 */
        FAILED_NEEDS_RESUME;

        public boolean canStart() {
            return this == NEW || this == RESUME;
        }
    }

    /** 处置结果。{@code existing} 是命中的那座设施（NEW 时为 null）。 */
    public record Decision(Disposition disposition, FacilityRecord existing, String reason) { }

    /**
     * 判断这次放置请求该怎么处置。
     *
     * @param registry 现有登记
     * @param facilityId 这次请求要用的设施 id（工具按"基地+模板+格"确定性生成）
     */
    public static Decision decide(FacilityRegistry registry, String facilityId) {
        if (registry == null || facilityId == null) {
            return new Decision(Disposition.NEW, null, "no existing ledger");
        }
        var existing = registry.byId(facilityId);
        if (existing.isEmpty()) {
            return new Decision(Disposition.NEW, null, "id 未被占用");
        }
        FacilityRecord f = existing.get();
        ConstructionProgress c = f.construction();
        if (c.status() == ConstructionStatus.UNTRACKED) {
            // 手工登记的历史设施占了同名 id：不能默默覆盖，让调用方显式换 id。
            return new Decision(Disposition.ALREADY_COMPLETE, f,
                    "id '" + facilityId + "' 已被手工登记的设施占用（无施工账）；换一个 id 或先 settlement_unregister");
        }
        return switch (c.status()) {
            case PLANNED, BUILDING, BLOCKED -> new Decision(Disposition.RESUME, f,
                    "已有施工中的设施 '" + facilityId + "'（" + c.status() + "，完成 "
                            + c.completed() + "/" + c.total() + "）；续建沿用原模板与锚点");
            case COMPLETE -> new Decision(Disposition.ALREADY_COMPLETE, f,
                    "设施 '" + facilityId + "' 已收口（" + c.completed() + "/" + c.total()
                            + "）；不重复建造。要重建先 settlement_unregister");
            case FAILED -> new Decision(Disposition.FAILED_NEEDS_RESUME, f,
                    "设施 '" + facilityId + "' 上次施工失败；确认要续建请显式 resume");
            case UNTRACKED -> new Decision(Disposition.ALREADY_COMPLETE, f,
                    "设施 '" + facilityId + "' 无施工账");
        };
    }

    /**
     * 确定性设施 id：同一 (基地, 模板, 格) 永远得到同一个 id。
     *
     * <p>这就是"重复调用识别已有设施"的钥匙——id 不是随机生成的，
     * 所以第二次同样的请求必然撞上第一次的账。
     */
    public static String facilityId(String baseId, String templateId, String cellName) {
        String b = baseId == null || baseId.isBlank() ? "base" : baseId;
        String t = templateId == null ? "tpl" : templateId;
        String c = cellName == null ? "?" : cellName;
        return sanitize(b + "_" + t + "_" + c.toLowerCase(java.util.Locale.ROOT));
    }

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(java.util.Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /**
     * 续建时校验：已有设施声明的模板/锚点必须和这次解算的一致，否则拒绝续建
     * （说明基地基准变了，照旧锚点建会错位）。
     */
    public static List<String> resumeMismatches(FacilityRecord existing, FacilityTemplate template,
                                                PlacementMath.PlacementPlan plan) {
        List<String> problems = new ArrayList<>();
        if (existing == null) {
            problems.add("没有可续建的设施");
            return problems;
        }
        ConstructionProgress c = existing.construction();
        if (c.template() != null && !c.template().equals(template.id())) {
            problems.add("模板不符：登记为 '" + c.template() + "'，本次为 '" + template.id() + "'");
        }
        if (c.anchor() != null) {
            var a = c.anchor();
            var p = plan.anchor();
            if (a.x() != p.x() || a.y() != p.y() || a.z() != p.z()) {
                problems.add("锚点不符：登记为 (" + a.x() + "," + a.y() + "," + a.z()
                        + ")，本次解算为 (" + p.x() + "," + p.y() + "," + p.z()
                        + ")；基地基准可能改过，续建会错位");
            }
        }
        if (existing.rotationQuarters() != plan.rotationQuarters()) {
            problems.add("朝向不符：登记为 " + existing.rotationQuarters() * 90 + "°，本次为 "
                    + plan.rotationQuarters() * 90 + "°");
        }
        return problems;
    }

    /** 设施登记里该记的施工账（开工前就写，这就是"先登记为施工中"）。 */
    public static ConstructionProgress openLedger(FacilityTemplate template,
                                                  PlacementMath.PlacementPlan plan,
                                                  String taskId, int totalCells, long at) {
        return ConstructionProgress.opened(template.id(), plan.anchor(), taskId, totalCells,
                ConstructionStatus.PLANNED, at);
    }

    /** 与设施边界一致的占位盒（登记用）。 */
    public static BlockBox boundsOf(PlacementMath.PlacementPlan plan) {
        return plan.footprintBox();
    }
}
