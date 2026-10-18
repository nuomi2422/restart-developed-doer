package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 验证助手：让<b>同伴自己</b>对指定坐标来一次真实的方块破坏，如实回报方块前后变化。
 *
 * <p>存在的理由：现有工具里没有"按坐标破坏/放置"的入口，而验证"保护是否真的拦住了同伴"
 * 又必须走同伴真实的破坏路径（这样 NeoForge 的 BreakEvent 才会带着 NumenPlayer 触发）。
 * 它不绕过保护——被保护就改不动，回报 {@code changed=false}。
 *
 * <p>它<b>确实是同伴在拆世界</b>，所以只用于对照验证（登记前/登记后各来一次）。
 */
public final class SettlementProbeBreakTool implements NumenTool {

    public SettlementProbeBreakTool() {}

    @Override public String name() { return "settlement_probe_break"; }

    @Override public String description() {
        return "验证助手：让你（同伴）对指定坐标尝试一次真实破坏，回报方块前后 id 与是否被改动。"
                + "用于对照验证设施保护——登记后对保护区里的方块调用应返回 changed=false。"
                + "它会真的改世界（未受保护时），只做验证用。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "目标方块 x", -30000000, 30000000)
                .integer("y", "目标方块 y", -64, 320)
                .integer("z", "目标方块 z", -30000000, 30000000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_probe_break: not on a server level").toJson());
                return;
            }
            if (!args.has("x") || !args.has("y") || !args.has("z")) {
                reply.accept(TaskResult.fail("settlement_probe_break needs x, y, z").toJson());
                return;
            }
            ServerLevel level = self.serverLevel();
            BlockPos pos = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
            if (!level.hasChunkAt(pos)) {
                reply.accept(TaskResult.fail("settlement_probe_break: chunk not loaded at " + pos.toShortString()).toJson());
                return;
            }
            String dimension = level.dimension().location().toString();
            BlockState before = level.getBlockState(pos);
            String beforeId = BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString();

            boolean destroyed = self.gameMode.destroyBlock(pos);

            BlockState after = level.getBlockState(pos);
            String afterId = BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString();
            boolean changed = !beforeId.equals(afterId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dimension", dimension);
            data.put("pos", pos.toShortString());
            data.put("before", beforeId);
            data.put("after", afterId);
            data.put("changed", changed);
            data.put("destroyed_reported", destroyed);
            String verdict = changed ? "block WAS changed" : "block was NOT changed (protected or unbreakable)";
            reply.accept(TaskResult.ok("probe break at " + pos.toShortString() + ": " + verdict, data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_probe_break error: " + ex.getMessage()).toJson());
        }
    }
}
