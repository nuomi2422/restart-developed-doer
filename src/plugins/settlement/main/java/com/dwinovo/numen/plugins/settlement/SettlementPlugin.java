package com.dwinovo.numen.plugins.settlement;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.settlement.tool.SettlementBaseTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementCountEntitiesTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementFarmTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementGrantTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementHouseTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementListTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementMapTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementPenTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementPlatformTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementProbeBreakTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementRegisterTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementRevokeTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementScanContainersTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementTradeTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementUnregisterTool;
import com.dwinovo.numen.plugins.settlement.tool.SettlementVerifyTool;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.FacilityRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** 基地与设施插件：登记真源 + 只读验收 + 工具入口。 */
public final class SettlementPlugin implements NumenPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("settlement");

    @Override
    public void setup(NumenApi numen) {
        SettlementService service = new SettlementService(numen.configDir());
        // 回填给 Mod 的 tick 清扫（施工授权"所有终止路径回收"要用它）。
        SettlementMod.bindService(service);
        // ★ 统一入口（基建工具 V1）：看地图 / 看模块 / 选格放置 / 查设施 / 续建。
        numen.registerTool(new SettlementTool(service));
        numen.registerTool(new SettlementRegisterTool(service));
        numen.registerTool(new SettlementListTool(service));
        numen.registerTool(new SettlementVerifyTool(service));
        numen.registerTool(new SettlementUnregisterTool(service));
        // 验证助手：按坐标让同伴真实破坏一次，用于对照验证保护是否生效。
        numen.registerTool(new SettlementProbeBreakTool());
        // 平台蓝图：生成"清空一片区域 + 可选铺地板"的蓝图，为建基地打底。
        numen.registerTool(new SettlementPlatformTool());
        // 牧场模块蓝图：5×5 栅栏圈 + 地毯门口的羊圈/牛圈。
        numen.registerTool(new SettlementPenTool());
        // 火柴盒核心屋：基地的第一块（床/箱/工作台/熔炉）。
        numen.registerTool(new SettlementHouseTool());
        // 交易所：7×7 两格高栅栏围墙 + 门 + 四张床（村民夜投床——用户 2026-10-09 定的做法）。
        numen.registerTool(new SettlementTradeTool());
        // 农田贴块生成器（中心留水孔）。
        numen.registerTool(new SettlementFarmTool());
        // 区域内实体计数（给 AC 判"圈内≥N 只"用）。
        numen.registerTool(new SettlementCountEntitiesTool());
        // 临时施工授权：同伴在自己设施的作业范围内合法施工（范围外照拦，过期/撤销即失效）。
        numen.registerTool(new SettlementGrantTool(service));
        numen.registerTool(new SettlementRevokeTool(service));
        // 登记设施里箱子的物品（只读快照）。
        numen.registerTool(new SettlementScanContainersTool(service));
        // 第一版基地网格地图 + 按格/按设施查详情。
        numen.registerTool(new SettlementMapTool(service));
        // 登记基地基准网格（整平地皮 → 固定原点/行列/每格边长）。
        numen.registerTool(new SettlementBaseTool(service));
        // 位置级保护落到执行层：在方块被改动前拦下同伴的动作（只拦同伴，不拦主人）。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(new SettlementProtectionListener(service));
        // 语义投影：把这名同伴登记的设施（用途/位置/范围/判定/物品数）挂进它每轮的 runtime_state，
        // 这样"它记得自己有什么设施"不靠模型记忆——每轮都在。
        numen.contributeState(uuid -> renderFacilities(service, uuid));
        // 规划投影：任务链规划器（Stage-A/B/回退）每次请求都看到己方设施——规划"设施任务"时的依据。
        numen.contributePlanningKnowledge(q -> renderFacilitiesPlanning(service,
                q == null ? null : q.companion()));
        LOG.info("[settlement] registered; store={} facilities={}", service.file(), service.list().size());
    }

    /**
     * 设施的只读语义投影（进 {@code <runtime_state>}）：一行一处，带 id/用途/维度/中心/大小/
     * 结构判定/生产判定/登记物品种类数。没有设施返回空串（不写空标签）。
     */
    private static String renderFacilities(SettlementService service, UUID uuid) {
        if (uuid == null) return "";
        String owner = uuid.toString();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (FacilityRecord f : service.list()) {
            if (!owner.equals(f.ownerId())) continue;
            if (n >= 24) break;
            if (sb.length() == 0) sb.append("<settlement>");
            n++;
            BlockBox b = f.bounds();
            sb.append("<facility id=\"").append(f.id())
                    .append("\" kind=\"").append(f.kind().name())
                    .append("\" dim=\"").append(f.dimension()).append('"')
                    .append(" center=\"").append((b.minX() + b.maxX()) / 2).append(',')
                    .append((b.minY() + b.maxY()) / 2).append(',')
                    .append((b.minZ() + b.maxZ()) / 2).append('"')
                    .append(" size=\"").append(b.sizeX()).append('x').append(b.sizeZ()).append('"')
                    .append(" structure=\"").append(f.structureVerdict()).append('"')
                    .append(" production=\"").append(f.productionState()).append('"');
            if (!f.storedItems().isEmpty()) {
                sb.append(" stored_types=\"").append(f.storedItems().size()).append('"');
            }
            sb.append("/>");
        }
        if (sb.length() == 0) return "";
        return sb.append("</settlement>").toString();
    }

    /**
     * 规划的只读投影：规划器每次请求都看到己方设施清单（id/用途/维度/中心/大小/判定/物品数）。
     * 让"给某设施加模块 / 修某牧场 / 判某农田"这类任务有据可依。没有设施返回空串。
     */
    private static String renderFacilitiesPlanning(SettlementService service, UUID uuid) {
        if (uuid == null) return "";
        String owner = uuid.toString();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (FacilityRecord f : service.list()) {
            if (!owner.equals(f.ownerId())) continue;
            if (n >= 24) break;
            if (sb.length() == 0) {
                sb.append("<facilities>\n已知的己方设施（登记真源在 settlement 模块；structure/production 是最近一次验收）：\n");
            }
            n++;
            BlockBox b = f.bounds();
            sb.append("- id=").append(f.id())
                    .append(" kind=").append(f.kind().name())
                    .append(" dim=").append(f.dimension())
                    .append(" center=").append((b.minX() + b.maxX()) / 2).append(',')
                    .append((b.minY() + b.maxY()) / 2).append(',')
                    .append((b.minZ() + b.maxZ()) / 2)
                    .append(" size=").append(b.sizeX()).append('x').append(b.sizeZ())
                    .append(" structure=").append(f.structureVerdict())
                    .append(" production=").append(f.productionState());
            if (!f.storedItems().isEmpty()) {
                sb.append(" stored_types=").append(f.storedItems().size());
            }
            sb.append('\n');
        }
        if (sb.length() == 0) return "";
        return sb.append("</facilities>").toString();
    }
}
