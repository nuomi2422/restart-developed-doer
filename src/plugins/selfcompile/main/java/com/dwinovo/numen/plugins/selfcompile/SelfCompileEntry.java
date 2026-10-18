package com.dwinovo.numen.plugins.selfcompile;

import com.dwinovo.numen.api.NumenPlugin;
import com.dwinovo.numen.plugins.selfcompile.generated.RddGetInventoryTool;
import com.dwinovo.numen.plugins.selfcompile.generated.RddWhereamiTool;
import net.neoforged.fml.ModList;

import java.nio.file.Path;

/**
 * Numen 插件入口：只做安装转发。
 *
 * <h2>★ 这个插件现在真正负责什么（2026-10-01 核实）</h2>
 *
 * <p><b>前两个工具：一个是查状态的闸门，一个默认关门（2026-10-01 第 4 轮核实后的准确描述）</b>
 * <ul>
 *   <li>{@code selfcompile_request} —— <b>默认关门</b>（见 {@link SelfCompilePolicy}）。
 *       它<b>不是</b>给游戏内 AI 的通道：外层 {@code run-mutation.ps1} 有自己的一套
 *       （生成 MutationId → 编译 → 验证 → 预算止损 → 部署 → 写证据），
 *       <b>根本不经过这个工具</b> —— 所以这个工具<b>当前没有任何合法调用方</b>。
 *       <b>没有合法调用方，就不需要识别「谁合法」，只需要默认关门。</b>
 *       游戏内 AI 要记需求请用 {@code learner_note}，外层读那些记录去做。</li>
 *   <li>{@code selfcompile_status} —— 查状态（idle / controlled）。</li>
 * </ul>
 *
 * <p><b>后两个是本系统自己生成的产物</b>（在 {@code generated/} 下），
 * 每完成一轮变异要手工更新这里的注册行——这就是自编译闭环的字面形态。</p>
 *
 * <p><b>已停用：</b>{@link MutationPipeline} 那条变异流水线（11 态状态机 / 编译 /
 * 验证 / 预算 / Harness 写锁）在生产代码里 <b>0 处引用</b>，从未接线。详见该类注释。
 * <b>不要在这里接线它</b>——真正的改码通道是外层
 * {@code rdd-selfcompile/scripts/run-mutation.ps1}（唯一改码入口），
 * 且它已有一套 MutationId 与门禁，两套并存必然漂移。
 */
public final class SelfCompileEntry implements NumenPlugin {

    @Override
    public void setup(com.dwinovo.numen.api.NumenApi numen) {
        Path root = numen.configDir().resolve("selfcompile").resolve("mutations");
        SelfCompileService service = new SelfCompileService(root);
        numen.registerTool(new SelfCompileStatusTool(service));
        // ★ RL-19 硬约束（2026-10-01 第 4 轮）：**默认关门**。
        //   策略文件 config/numen/selfcompile_policy.json 里写 {"allowInGameRequest":true} 才开门。
        //   外层 run-mutation.ps1 走自己的通道（不经过这个工具），所以关门不影响它。
        numen.registerTool(new SelfCompileRequestTool(
                service, SelfCompilePolicy.load(numen.configDir())));
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
