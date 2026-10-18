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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 生成一块<b>农田</b>蓝图：正方形 {@code farmland}，正中留一格水孔（空气）——
 * 蓝图画不了液体，那一格要另用桶灌水（一个水源能润 9×9 内的耕地）。
 *
 * <p>放到 {@code schematics/}，用 {@code blueprint action=build} 施工。
 * 以核心基地的坐标为准在旁边"贴"一块即可。耕地没有水会退回泥土，所以灌水这步不能省。
 */
public final class SettlementFarmTool implements NumenTool {

    private static final int MIN_SIZE = 3;
    private static final int MAX_SIZE = 15;
    private static final String DIRT = "minecraft:dirt";
    private static final String FARMLAND = "minecraft:farmland";

    public SettlementFarmTool() {}

    @Override public String name() { return "settlement_farm"; }

    @Override public String description() {
        return "生成一块农田蓝图：正方形耕地（farmland），正中留一格水孔（空气）。"
                + "图写到 schematics/，随后用 blueprint action=build file=<返回的 blueprint> "
                + "x/y/z=<返回的 anchor> 施工。size 默认 9（奇数，一个中心水源刚好润整块）。"
                + "★ 水孔要另用桶灌水（蓝图跳过液体格）；耕地没水会退回泥土。生存需要 dirt×约 2*size*size。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("size", "正方形边长（奇数），默认 9", MIN_SIZE, MAX_SIZE)
                .optionalInteger("floor_y", "耕地所在 Y，默认你脚下那格", -64, 320)
                .optionalString("name", "蓝图名（不含扩展名），默认 farm_<size>")
                // 统一放置入口按格算好坐标后显式传入，不以站位为中心。
                .optionalInteger("cx", "显式中心 x（不给则用你当前站位）", -30000000, 30000000)
                .optionalInteger("cz", "显式中心 z", -30000000, 30000000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_farm: not on a server level").toJson());
                return;
            }
            int size = clamp(args.has("size") ? args.get("size").getAsInt() : 9, MIN_SIZE, MAX_SIZE);
            if (size % 2 == 0) size += 1;
            BlockPos center = self.blockPosition();
            int floorY = args.has("floor_y") ? args.get("floor_y").getAsInt() : center.getY() - 1;
            String name = args.has("name") && !args.get("name").getAsString().isBlank()
                    ? sanitize(args.get("name").getAsString()) : ("farm_" + size);

            int half = size / 2;
            // 显式中心优先（统一放置入口按格算好坐标后传进来），否则退回站位。
            int cx = args.has("cx") ? args.get("cx").getAsInt() : center.getX();
            int cz = args.has("cz") ? args.get("cz").getAsInt() : center.getZ();
            int minX = cx - half;
            int minZ = cz - half;

            CompoundTag tag = buildTag(size);
            MinecraftServer server = self.getServer();
            Path dir = server.getServerDirectory().resolve("schematics");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".nbt");
            Files.deleteIfExists(dir.resolve(name + ".snbt"));
            Files.deleteIfExists(file);
            NbtIo.writeCompressed(tag, file);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("blueprint", name);
            data.put("file", file.toString());
            data.put("size", size + "x" + size);
            data.put("cells", size * size * 2);   // 支撑 + 耕地，每格两格图纸
            data.put("floor_y", floorY);
            data.put("anchor", minX + "," + (floorY - 1) + "," + minZ);
            data.put("water_hole", (minX + half) + "," + floorY + "," + (minZ + half));
            data.put("next", "blueprint action=build file=" + name
                    + " x=" + minX + " y=" + (floorY - 1) + " z=" + minZ);
            data.put("note", "水孔坐标见 water_hole，施工后用桶灌水");
            reply.accept(TaskResult.ok("farm blueprint '" + name + "' written to schematics/", data).toJson());
        } catch (IOException | RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_farm error: " + ex.getMessage()).toJson());
        }
    }

    /**
     * size×2×size：y=0 一层 dirt（支撑），y=1 一层 farmland（正中一格为空气=水孔）。
     * 锚点 y = floorY-1。
     */
    private static CompoundTag buildTag(int size) {
        CompoundTag root = new CompoundTag();
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(size));
        sizeTag.add(IntTag.valueOf(2));
        sizeTag.add(IntTag.valueOf(size));
        root.put("size", sizeTag);

        ListTag palette = new ListTag();
        palette.add(nameTag(DIRT));       // 0
        palette.add(nameTag(FARMLAND));   // 1
        palette.add(nameTag("minecraft:air")); // 2
        root.put("palette", palette);

        int c = size / 2;
        ListTag blocks = new ListTag();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                blocks.add(cellTag(x, 0, z, 0));   // 支撑 dirt
                boolean hole = (x == c && z == c);
                blocks.add(cellTag(x, 1, z, hole ? 2 : 1)); // 耕地 / 中心水孔=空气
            }
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static CompoundTag nameTag(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
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
        return s.isBlank() ? "farm" : s;
    }
}
