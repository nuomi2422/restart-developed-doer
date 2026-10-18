package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.plugins.settlement.McWorldProbe;
import com.dwinovo.numen.plugins.settlement.SettlementService;
import com.dwinovo.numen.settlement.core.accept.AcceptanceEvaluator;
import com.dwinovo.numen.settlement.core.logic.CompletionChecker;
import com.dwinovo.numen.settlement.core.logic.TemplateCatalog;
import com.dwinovo.numen.settlement.core.model.ConstructionProgress;
import com.dwinovo.numen.settlement.core.model.ConstructionStatus;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import com.dwinovo.numen.settlement.core.model.FacilityTemplate;
import com.dwinovo.numen.settlement.core.model.ProductionState;
import com.dwinovo.numen.settlement.core.model.Verdict;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 验收一处设施：结构可用（第 2 层）与生产条件（第 3 层）。
 *
 * <p>与施工对账分开：建造工具结束不代表设施可用。区块未加载一律回 UNKNOWN 并说明原因，
 * 不用旧缓存冒充当前事实；结果回写登记（含检查时间）。
 */
public final class SettlementVerifyTool implements NumenTool {

    private final SettlementService service;

    public SettlementVerifyTool(SettlementService service) {
        this.service = service;
    }

    @Override public String name() { return "settlement_verify"; }

    @Override public String description() {
        return "复查一处已登记设施：结构是否可用（围栏闭合/入口可通行一类的关键条件）"
                + "与生产条件是否具备（圈内已有羊、农田已有作物、交易位有村民）。"
                + "两者分开：空羊圈可以『结构可用』但『生产未就绪』。区块未加载时回 UNKNOWN，"
                + "不假装设施消失。结果会写回登记。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "要验收的设施 id")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (!args.has("id")) {
                reply.accept(TaskResult.fail("settlement_verify needs 'id'").toJson());
                return;
            }
            String id = args.get("id").getAsString().toLowerCase(java.util.Locale.ROOT).trim();
            Optional<FacilityRecord> found = service.byId(id);
            if (found.isEmpty()) {
                reply.accept(TaskResult.fail("settlement_verify: no facility '" + id + "' (use settlement_list)").toJson());
                return;
            }
            if (self == null || self.getServer() == null) {
                reply.accept(TaskResult.fail("settlement_verify: no server").toJson());
                return;
            }
            FacilityRecord facility = found.get();
            McWorldProbe probe = new McWorldProbe(self.getServer());
            Verdict structure = AcceptanceEvaluator.structureVerdict(facility, probe);
            ProductionState production = AcceptanceEvaluator.productionState(facility, probe);

            // 施工收口的<b>世界侧</b>核对：任务说"结束"不等于"建好了"。
            // 有模板与锚点时按结构标记逐个核对，把结果回写施工账——这样"还差哪几格"
            // 在任务被顶替/重启后依然答得出来（答案来自世界，不是任务的计数）。
            FacilityRecord updated = facility;
            CompletionChecker.Result completion = null;
            ConstructionProgress c = facility.construction();
            if (c.template() != null && c.anchor() != null) {
                var tpl = TemplateCatalog.byId(c.template());
                if (tpl.isPresent() && !tpl.get().marks().isEmpty()) {
                    FacilityTemplate t = tpl.get();
                    completion = CompletionChecker.check(t.marks(), c.anchor(),
                            t.sizeX(), t.sizeZ(), facility.rotationQuarters(), probe);
                    ConstructionStatus next = c.status();
                    if (completion.complete()) {
                        next = ConstructionStatus.COMPLETE;
                    } else if (c.status() == ConstructionStatus.BUILDING
                            || c.status() == ConstructionStatus.PLANNED) {
                        next = ConstructionStatus.BLOCKED;
                    }
                    ConstructionProgress ledger = new ConstructionProgress(
                            completion.matched(), c.skipped(), c.droppedAtLoad(),
                            completion.total(), System.currentTimeMillis(),
                            next, c.template(), c.anchor(), c.taskId(), c.missing(),
                            completion.complete() ? "世界核对：结构标记全部命中"
                                    : "世界核对：还差 " + completion.missingCount() + " 处结构标记");
                    updated = facility.withConstruction(ledger);
                }
            }
            updated = updated.withAcceptance(structure, production, System.currentTimeMillis());
            service.register(updated);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("kind", facility.kind().name());
            data.put("structure", structure.name());
            data.put("production", production.name());
            data.put("construction_status", updated.construction().status().name());
            data.put("construction_reconciled", updated.construction().reconciled());
            data.put("construction_total", updated.construction().total());
            data.put("construction_outstanding", updated.construction().outstanding());
            if (completion != null) {
                data.put("marks_matched", completion.matched());
                data.put("marks_missing", completion.missing());
            }
            data.put("checked_at", updated.lastCheckedAtEpochMs());
            reply.accept(TaskResult.ok("facility '" + id + "': structure=" + structure
                    + ", production=" + production
                    + (completion == null ? "" : ", marks=" + completion.matched() + "/" + completion.total())
                    , data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_verify error: " + ex.getMessage()).toJson());
        }
    }
}
