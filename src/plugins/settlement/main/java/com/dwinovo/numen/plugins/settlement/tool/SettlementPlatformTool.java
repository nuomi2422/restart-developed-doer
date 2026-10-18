package com.dwinovo.numen.plugins.settlement.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 生成一块<b>整平平台</b>蓝图：把一块正方形地皮整成同一个水平面。
 *
 * <p>给 {@code floor_block} 时做<b>动态整平</b>——逐列读当前地形：低的地方从目标层往下填实，
 * 高的地方削掉，顶面统一铺一层 {@code floor_block}，再清空上方 {@code clear_height} 层。
 * 结果是一块<b>顶面齐平、下面是实的</b>地皮（不是飘着的薄板）。
 * 不给 {@code floor_block} 时退化为"只清空上方"。
 *
 * <p>图写到 {@code schematics/}，再用 {@code blueprint action=build} 施工。这是模块化基地的
 * 第一层；整好后可用 {@code settlement_base} 把这块地皮登记为基地基准，{@code settlement_map} 出网格图。
 */
public final class SettlementPlatformTool implements NumenTool {

    private static final int MIN_SIZE = 3;
    private static final int MAX_SIZE = 64;
    private static final int MAX_CLEAR_HEIGHT = 16;
    private static final int MAX_DEPTH = 12;
    /** 与原版结构上限一致的安全线（BlueprintStore.MAX_CELLS=32768）。 */
    private static final long MAX_CELLS = 32000;

    public SettlementPlatformTool() {}

    @Override public String name() { return "settlement_platform"; }

    @Override public String description() {
        return "生成一块整平平台蓝图：以你站位为中心的正方形地皮，逐列把地形整成同一个水平面"
                + "（低的填到目标层、高的削掉、顶面统一铺 floor_block），再清空上方 clear_height 层。"
                + "floor_block 给方块 id（如 minecraft:dirt）→ 动态整平；不给 → 只清空上方。"
                + "size 边长默认 33（可更大，受方块数上限约束）；floor_y 目标层默认你脚下；"
                + "depth 是往下填的最大深度（默认 5，越深越能填平大坑）。"
                + "生成后用 blueprint action=build file=<blueprint> x/y/z=<anchor> 施工，x/y/z 原样用返回的 anchor。"
                + "注意：清空/填充都要你的区块已加载；生存下填充要备料。液体格会被跳过。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("size", "正方形边长，默认 33", MIN_SIZE, MAX_SIZE)
                .optionalInteger("clear_height", "顶面上方清空的层数，默认 4", 1, MAX_CLEAR_HEIGHT)
                .optionalInteger("depth", "往下填平的最大深度，默认 5", 1, MAX_DEPTH)
                .optionalString("floor_block", "顶面方块 id，如 minecraft:dirt；给了才做整平，不给只清空")
                .optionalInteger("floor_y", "目标水平面 Y，默认你脚下那格", -64, 320)
                .optionalString("name", "蓝图名（不含扩展名），默认 platform_<size>")
                // 统一放置入口按格算好坐标后显式传入，不以站位为中心。
                .optionalInteger("cx", "显式中心 x（不给则用你当前站位）", -30000000, 30000000)
                .optionalInteger("cz", "显式中心 z", -30000000, 30000000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_platform: not on a server level").toJson());
                return;
            }
            int size = clamp(args.has("size") ? args.get("size").getAsInt() : 33, MIN_SIZE, MAX_SIZE);
            int clearHeight = clamp(args.has("clear_height") ? args.get("clear_height").getAsInt() : 4,
                    1, MAX_CLEAR_HEIGHT);
            int maxDepth = clamp(args.has("depth") ? args.get("depth").getAsInt() : 5, 1, MAX_DEPTH);
            String floorBlock = args.has("floor_block") && !args.get("floor_block").isJsonNull()
                    ? args.get("floor_block").getAsString().trim() : null;
            // 显式中心优先（统一放置入口按格算好坐标后传进来），否则退回站位。
            int centerX = args.has("cx") ? args.get("cx").getAsInt() : self.blockPosition().getX();
            int centerZ = args.has("cz") ? args.get("cz").getAsInt() : self.blockPosition().getZ();
            int floorY = args.has("floor_y") ? args.get("floor_y").getAsInt() : self.blockPosition().getY() - 1;
            String bpName = args.has("name") && !args.get("name").getAsString().isBlank()
                    ? sanitize(args.get("name").getAsString()) : ("platform_" + size);

            int half = size / 2;
            int minX = centerX - half;
            int minZ = centerZ - half;
            int anchorY = floorBlock != null ? floorY - maxDepth : floorY;
            int sy = (floorBlock != null ? maxDepth : 0) + clearHeight + 1;
            long cells = (long) size * size * sy;
            if (cells > MAX_CELLS) {
                reply.accept(TaskResult.fail("settlement_platform: " + size + "x" + size + " depth=" + maxDepth
                        + " clear=" + clearHeight + " needs " + cells + " cells > " + MAX_CELLS
                        + "; reduce size/depth/clear_height").toJson());
                return;
            }

            ServerLevel level = self.serverLevel();
            // 需要整个地块已加载才能逐列读地形。
            if (!areaLoaded(level, minX, minZ, size)) {
                reply.accept(TaskResult.fail("settlement_platform: chunk not loaded over the whole "
                        + size + "x" + size + " area at " + minX + "," + floorY + "," + minZ
                        + " — go there first").toJson());
                return;
            }

            Build build = floorBlock != null
                    ? buildLevelTag(level, size, minX, minZ, floorY, anchorY, maxDepth, clearHeight, floorBlock)
                    : buildClearTag(size, clearHeight);

            MinecraftServer server = self.getServer();
            Path dir = server.getServerDirectory().resolve("schematics");
            Files.createDirectories(dir);
            Path file = dir.resolve(bpName + ".nbt");
            Files.deleteIfExists(dir.resolve(bpName + ".snbt"));
            Files.deleteIfExists(file);
            NbtIo.writeCompressed(build.tag(), file);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("blueprint", bpName);
            data.put("file", file.toString());
            data.put("size", size + "x" + size);
            data.put("floor_y", floorY);
            data.put("mode", floorBlock != null ? "level" : "clear");
            data.put("floor_block", floorBlock == null ? "(none: clear only)" : floorBlock);
            data.put("cells", build.cells());
            data.put("filled", build.filled());
            data.put("cleared", build.cleared());
            data.put("anchor", minX + "," + anchorY + "," + minZ);
            data.put("next", "blueprint action=build file=" + bpName
                    + " x=" + minX + " y=" + anchorY + " z=" + minZ);
            reply.accept(TaskResult.ok("platform blueprint '" + bpName + "' (" + (floorBlock != null ? "leveled" : "cleared")
                    + ") written to schematics/", data).toJson());
        } catch (IOException | RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_platform error: " + ex.getMessage()).toJson());
        }
    }

    private record Build(CompoundTag tag, int cells, int filled, int cleared) {}

    /** 动态整平：逐列填低削高，顶面铺 floor_block，清空上方。 */
    private static Build buildLevelTag(ServerLevel level, int size, int minX, int minZ, int floorY,
                                       int anchorY, int maxDepth, int clearHeight, String floorBlock) {
        CompoundTag root = new CompoundTag();
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(size));
        sizeTag.add(IntTag.valueOf(maxDepth + clearHeight + 1));
        sizeTag.add(IntTag.valueOf(size));
        root.put("size", sizeTag);

        ListTag palette = new ListTag();
        palette.add(nameTag(floorBlock));
        palette.add(nameTag("minecraft:air"));
        root.put("palette", palette);

        ListTag blocks = new ListTag();
        int filled = 0;
        int cleared = 0;
        for (int dx = 0; dx < size; dx++) {
            int wx = minX + dx;
            for (int dz = 0; dz < size; dz++) {
                int wz = minZ + dz;
                int top = Integer.MIN_VALUE;
                for (int y = floorY + clearHeight; y >= anchorY; y--) {
                    if (!level.getBlockState(new BlockPos(wx, y, wz)).isAir()) { top = y; break; }
                }
                int fillStart = (top == Integer.MIN_VALUE) ? anchorY : Math.max(top + 1, anchorY);
                for (int y = fillStart; y < floorY; y++) {
                    blocks.add(cellTag(dx, y - anchorY, dz, 0));
                    filled++;
                }
                blocks.add(cellTag(dx, floorY - anchorY, dz, 0));   // 顶面统一铺
                filled++;
                for (int y = floorY + 1; y <= floorY + clearHeight; y++) {
                    blocks.add(cellTag(dx, y - anchorY, dz, 1));     // 清空上方
                    cleared++;
                }
            }
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return new Build(root, blocks.size(), filled, cleared);
    }

    private static Build buildClearTag(int size, int clearHeight) {
        CompoundTag root = new CompoundTag();
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(size));
        sizeTag.add(IntTag.valueOf(clearHeight));
        sizeTag.add(IntTag.valueOf(size));
        root.put("size", sizeTag);
        ListTag palette = new ListTag();
        palette.add(nameTag("minecraft:air"));
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = 0; y < clearHeight; y++) {
                    blocks.add(cellTag(x, y, z, 0));
                }
            }
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return new Build(root, blocks.size(), 0, blocks.size());
    }

    private static CompoundTag nameTag(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    private static boolean areaLoaded(ServerLevel level, int minX, int minZ, int size) {
        int maxX = minX + size - 1;
        int maxZ = minZ + size - 1;
        for (int[] c : new int[][]{{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ},
                {(minX + maxX) / 2, (minZ + maxZ) / 2}}) {
            if (!level.hasChunkAt(new BlockPos(c[0], level.getMinBuildHeight(), c[1]))) return false;
        }
        return true;
    }

    private static CompoundTag cellTag(int x, int y, int z, int state) {
        CompoundTag cell = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        cell.put("pos", pos);
        cell.putInt("state", state);
        return cell;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
        return s.isBlank() ? "platform" : s;
    }
}
