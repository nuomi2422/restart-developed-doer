package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.selfcompile.generated.RddGetInventoryTool;
import com.dwinovo.numen.plugins.selfcompile.generated.RddWhereamiTool;
import net.neoforged.fml.ModList;

import java.nio.file.Path;

/**
 * Numen 插件入口：只做安装转发。
 */
public final class SelfCompileEntry implements NumenPlugin {

    @Override
    public void setup(com.dwinovo.numen.api.NumenApi numen) {
        Path root = numen.configDir().resolve("selfcompile").resolve("mutations");
        SelfCompileService service = new SelfCompileService(root);
        numen.registerTool(new SelfCompileStatusTool(service));
        numen.registerTool(new SelfCompileRequestTool(service));
        // Self-Compile 自变异系统生成的工具（每轮变异后更新这里）
        numen.registerTool(new RddWhereamiTool());
        numen.registerTool(new RddGetInventoryTool());

        Path skills = ModList.get().getModFileById(SelfCompileMod.MOD_ID)
                .getFile().findResource("skills");
        if (skills != null) numen.bundleSkills(skills);

        numen.onClient(() -> {
            // 预留：后续挂自变异 UI / 状态面板。
        });
        numen.contributeState(uuid -> "<selfcompile><state>controlled</state></selfcompile>");
    }
}
