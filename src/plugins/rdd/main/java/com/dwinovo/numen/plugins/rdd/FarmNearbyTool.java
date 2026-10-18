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
 * 一次调用把**身边一片田**里的成熟作物全部“收 + 原地留苗”（对齐车万女仆 TaskNormalFarm.harvest）。
 *
 * <p>为什么要有它（2026-10-08 实测）：ACX 的 {@code for} 循环体里只要有<b>异步</b>步（如 {@code goto}），
 * 一旦暂停再 resume，循环会从第 0 项重来 → 永远出不去第一株。所以“逐株 goto+farm_cell”的循环写不出来。
 * 把整片处理收进一个工具，AC 就只需一次同步调用，不带异步循环。
 *
 * <p>语义与 {@link FarmCellTool} 单格一致：成熟作物 → 产物进背包（满则就地掉落）+ age 复位为 0，不拆块、不耗种子。
 * 只碰**成熟作物**，空格/嫩苗不动。纯坐标世界操作（不需要逐株寻路）。
 */
final class FarmNearbyTool implements NumenTool {

    static final int MAX_RADIUS = 32;

    @Override
    public String name() {
        return "farm_nearby";
    }

    @Override
    public String description() {
        return "Harvest and immediately regrow EVERY mature crop in a nearby patch in one call. For each mature "
                + "crop: the produce goes into your inventory and the crop is reset to age 0 (no seed consumed, the "
                + "block stays) — the native-maid way. Only mature crops are touched; empty cells and seedlings are "
                + "left alone. Reports {harvested}. Does not require walking to each crop.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("radius", "Horizontal radius of the patch (1-" + MAX_RADIUS + ").", 1, MAX_RADIUS)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            int radius = 12;
            if (args != null && args.has("radius") && !args.get("radius").isJsonNull()) {
                radius = args.get("radius").getAsInt();
            }
            radius = Math.max(1, Math.min(MAX_RADIUS, radius));
            if (!(self.level() instanceof ServerLevel level)) {
                reply.accept(TaskResult.fail("farm_nearby: not on a server level").toJson());
                return;
            }
            BlockPos center = self.blockPosition();
            int harvested = 0;
            BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    for (int dy = -4; dy <= 4; dy++) {
                        cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!level.hasChunkAt(cur)) continue;
                        BlockState st = level.getBlockState(cur);
                        if (!ScanMatureCropsTool.isMature(st)) continue;
                        IntegerProperty age = ageProperty(st);
                        if (age == null) continue;
                        BlockPos pos = cur.immutable();
                        BlockEntity be = st.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                        List<ItemStack> drops = Block.getDrops(st, level, pos, be, self, self.getMainHandItem());
                        for (ItemStack drop : drops) {
                            if (!drop.isEmpty() && !self.getInventory().add(drop)) {
                                self.drop(drop, false);
                            }
                        }
                        level.setBlock(pos, st.setValue(age, age.getPossibleValues().iterator().next()),
                                Block.UPDATE_ALL);
                        harvested++;
                    }
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("harvested", harvested);
            data.put("radius", radius);
            reply.accept(TaskResult.ok("harvested + regrown " + harvested + " mature crop(s) in radius " + radius,
                    data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("farm_nearby error: " + ex.getMessage()).toJson());
        }
    }

    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof IntegerProperty ip && "age".equals(p.getName())
                    && ip.getPossibleValues().contains(0)) {
                return ip;
            }
        }
        return null;
    }
}
