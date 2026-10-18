package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 原子「收 + 原地留苗」工具（Path B，对齐原生车万女仆 {@code TaskNormalFarm.harvest}）。
 *
 * <p>给一格成熟作物的坐标：把产物收进背包、并把<b>该作物 reset 回 age=0</b>（原地留成嫩苗）。
 * <b>不消耗种子、不拆方块</b> —— 这正是女仆的做法：收割=「先扣产物、再把 age 设回 0、setBlock」。
 * 一步到位、天然原子，从结构上没有「挖了没种」的脏状态。
 *
 * <p>要么全成、要么不动：非成熟作物 / 太远 / 未加载 / 该作物没有可复位的 age 属性 —— 明确失败、什么都不改。
 * 纯坐标世界操作，不占身体任务；调用前用 {@code goto} 走到跟前（reach 内即可）。回执即时，ACX 桥可靠拿到。
 *
 * <p>「成熟」判据与 {@link ScanMatureCropsTool#isMature} 同源（含小麦/胡萝卜/土豆/甜菜根/下界疣/甜浆果/可可）。
 */
final class FarmCellTool implements NumenTool {

    private static final double REACH = 5.5;

    @Override
    public String name() {
        return "farm_cell";
    }

    @Override
    public String description() {
        return "Atomically harvest ONE mature crop at (x,y,z) and leave it regrown in place: the produce goes into "
                + "your inventory and the crop is reset to age 0 (no seed consumed, the block is not removed) — the "
                + "same way the native maid farms. ALL-OR-NOTHING: if the cell isn't a mature crop, you're not within "
                + "reach, the chunk isn't loaded, or the crop has no age to reset, it FAILS and changes NOTHING. goto "
                + "the cell first. Reports {block, harvested}. Does not travel.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Crop cell X.", -30_000_000, 30_000_000)
                .integer("y", "Crop cell Y.", -30_000_000, 30_000_000)
                .integer("z", "Crop cell Z.", -30_000_000, 30_000_000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            Integer x = intOrNull(args, "x");
            Integer y = intOrNull(args, "y");
            Integer z = intOrNull(args, "z");
            if (x == null || y == null || z == null) {
                reply.accept(TaskResult.fail("farm_cell needs integer x, y, z").toJson());
                return;
            }
            if (!(self.level() instanceof ServerLevel level)) {
                reply.accept(TaskResult.fail("farm_cell: not on a server level").toJson());
                return;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                reply.accept(TaskResult.fail("farm_cell: chunk at " + x + "," + y + "," + z + " is not loaded").toJson());
                return;
            }
            if (self.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > REACH * REACH) {
                reply.accept(TaskResult.fail("farm_cell: too far from " + x + "," + y + "," + z + " — goto it first").toJson());
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (!ScanMatureCropsTool.isMature(state)) {
                reply.accept(TaskResult.fail("farm_cell: no MATURE crop at " + x + "," + y + "," + z
                        + " (found " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "); nothing changed").toJson());
                return;
            }
            IntegerProperty age = ageProperty(state);
            if (age == null) {
                reply.accept(TaskResult.fail("farm_cell: " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                        + " has no age to reset; nothing changed").toJson());
                return;
            }

            // ---- 原子区：前置全满足才动手 ----
            // 1) 产物进背包（满则掉落就地），对齐女仆 dropResourcesToMaidInv
            BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            List<ItemStack> drops = Block.getDrops(state, level, pos, be, self, self.getMainHandItem());
            for (ItemStack drop : drops) {
                if (!drop.isEmpty() && !self.getInventory().add(drop)) {
                    self.drop(drop, false);
                }
            }
            // 2) 原地留苗：age 设回 0，不拆块、不耗种子
            BlockState regrown = state.setValue(age, age.getPossibleValues().iterator().next());
            level.setBlock(pos, regrown, Block.UPDATE_ALL);
            BlockState after = level.getBlockState(pos);
            if (after.getValue(age) == state.getValue(age)) {
                reply.accept(TaskResult.fail("farm_cell: reset did not take at " + x + "," + y + "," + z).toJson());
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("x", x);
            data.put("y", y);
            data.put("z", z);
            data.put("harvested", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            data.put("drops", drops.size());
            data.put("regrown", true);
            reply.accept(TaskResult.ok("harvested + regrown (age reset) "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + x + "," + y + "," + z, data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("farm_cell error: " + ex.getMessage()).toJson());
        }
    }

    /** 方块状态里名为 {@code age} 的整数属性（作物都有；拿不到=没法复位，判失败）。 */
    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof IntegerProperty ip && "age".equals(p.getName())
                    && ip.getPossibleValues().contains(0)) {
                return ip;
            }
        }
        return null;
    }

    private static Integer intOrNull(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
