package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读：找附近【成熟】的作物（判断方块 age 是否到顶），最近优先，最多 32 个。
 *
 * <p>种田 AC 的“扫成熟作物”这一步。未成熟的<b>不返回</b>，所以照着它去割永远不会误伤还在长的作物
 * —— 这是现有 {@code scan_blocks}（只按 block id）判不出来的那件事。
 *
 * <p>输出形状对齐 {@code scan_blocks}：裸 JSON {@code {crops:[{x,y,z,block,distance}]}}，
 * 便于 ACX 用 {@code $filter/$pick nearest/$take} 或 {@code for} 直接消费。
 */
final class ScanMatureCropsTool implements NumenTool {

    static final int MAX_RADIUS = 32;
    static final int MAX_RESULTS = 32;

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "scan_mature_crops";
    }

    @Override
    public String description() {
        return "Read-only: find MATURE (fully-grown) crops near you — wheat, carrots, potatoes, beetroots, "
                + "nether_wart, sweet_berry_bush, cocoa — nearest first, up to 32. Only fully-grown crops are "
                + "returned, so harvesting them never destroys a still-growing one. Output "
                + "{crops:[{x,y,z,block,distance}]}. Survey only; to gather, walk there and use mine.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("radius", "Spherical search radius in blocks (1-" + MAX_RADIUS + ").", 1, MAX_RADIUS)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        int radius = 16;
        try {
            if (args != null && args.has("radius") && !args.get("radius").isJsonNull()) {
                radius = args.get("radius").getAsInt();
            }
        } catch (RuntimeException ignore) {
            // keep default
        }
        radius = Math.max(1, Math.min(MAX_RADIUS, radius));

        if (!(self.level() instanceof ServerLevel level)) {
            reply.accept("{\"crops\":[],\"count\":0,\"note\":\"not on a server level\"}");
            return;
        }
        BlockPos center = self.blockPosition();
        List<Hit> hits = new ArrayList<>();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > r2 || planar + dy * dy > r2) continue;
                    cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.hasChunkAt(cur)) continue;
                    BlockState st = level.getBlockState(cur);
                    if (!isMature(st)) continue;
                    hits.add(new Hit(cur.immutable(),
                            BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(),
                            seedItemFor(st),
                            Math.sqrt(center.distSqr(cur))));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        int limit = Math.min(hits.size(), MAX_RESULTS);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < limit; i++) {
            Hit h = hits.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", h.pos().getX());
            o.addProperty("y", h.pos().getY());
            o.addProperty("z", h.pos().getZ());
            o.addProperty("block", h.block());
            if (h.seed() != null) {
                o.addProperty("seed", h.seed());
            }
            o.addProperty("distance", h.distance());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("crops", arr);
        root.addProperty("count", hits.size());
        root.addProperty("radius_searched", radius);
        root.addProperty("truncated", hits.size() > MAX_RESULTS);
        JsonObject c = new JsonObject();
        c.addProperty("x", center.getX());
        c.addProperty("y", center.getY());
        c.addProperty("z", center.getZ());
        root.add("center", c);
        reply.accept(GSON.toJson(root));
    }

    /** 成熟判据：作物方块 age 到顶才算。只认“会再长/割了有产出”的作物。 */
    static boolean isMature(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock crop) {
            return crop.isMaxAge(s);
        }
        if (b instanceof NetherWartBlock && s.hasProperty(NetherWartBlock.AGE)) {
            return s.getValue(NetherWartBlock.AGE) >= 3;
        }
        if (b instanceof SweetBerryBushBlock && s.hasProperty(SweetBerryBushBlock.AGE)) {
            return s.getValue(SweetBerryBushBlock.AGE) >= 3;
        }
        if (b instanceof CocoaBlock && s.hasProperty(CocoaBlock.AGE)) {
            return s.getValue(CocoaBlock.AGE) >= 2;
        }
        return false;
    }

    /** 该作物补种回去要用哪个物品（给 interact_at 的 item_id）；认不出返回 null。 */
    static String seedItemFor(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.WHEAT) return "minecraft:wheat_seeds";
        if (b == Blocks.CARROTS) return "minecraft:carrot";
        if (b == Blocks.POTATOES) return "minecraft:potato";
        if (b == Blocks.BEETROOTS) return "minecraft:beetroot_seeds";
        if (b == Blocks.NETHER_WART) return "minecraft:nether_wart";
        if (b == Blocks.SWEET_BERRY_BUSH) return "minecraft:sweet_berries";
        if (b == Blocks.COCOA) return "minecraft:cocoa_beans";
        return null;
    }

    private record Hit(BlockPos pos, String block, String seed, double distance) {
    }
}
