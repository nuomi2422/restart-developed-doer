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
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 生成<b>交易所</b>蓝图（7×7）：两格高的栅栏围墙 + 北侧中间一道栅栏门（门上方<b>留空</b>，
 * 否则寻路开门会被卡住）+ 内部四张床（两排，全部朝北）。
 *
 * <p>用户 2026-10-09 定的做法："交易所可以搭两格高的围墙，在里面放满床。村民到晚上
 * 如果周围没床，就会自己跑过去睡觉。然后就把那个给封起来，栅栏门进出即可。"
 * ——所以这个模块<b>不生成</b>工作站/交易位，只生成围墙与床；村民由夜间寻床本能自己走进来，
 * 门先开后关（关门由 {@code GateWatcher} 自动做）。
 *
 * <p>图写到 {@code schematics/}，再用 {@code blueprint action=build} 施工。
 * 墙 y=0/y=1 两层，床脚在 y=0（站在平台地面上）。
 */
public final class SettlementTradeTool implements NumenTool {

    private static final int SIZE = 7;
    private static final String FENCE = "minecraft:oak_fence";
    private static final String GATE = "minecraft:oak_fence_gate";
    private static final String BED = "minecraft:white_bed";

    public SettlementTradeTool() {}

    @Override public String name() { return "settlement_trade"; }

    @Override public String description() {
        return "生成 7×7 交易所蓝图：两格高栅栏围墙（46 栅栏），北侧中间一道栅栏门（门上方留空），"
                + "内部四张床（2 排 × 2 张，床头朝北）。村民夜里自己找床睡，封起来即可当交易所。"
                + "cx/cy/cz 可显式指定中心（统一放置入口按格算好坐标后会这么传）。"
                + "图写到 schematics/，随后用 blueprint action=build file=<返回的 blueprint> x/y/z=<返回的 anchor> 施工。"
                + "生存需要 oak_fence×46 + oak_fence_gate×1 + white_bed×4。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("name", "蓝图名（不含扩展名），默认 trade_post")
                .optionalInteger("cx", "显式中心 x（不给则用你当前站位）", -30000000, 30000000)
                .optionalInteger("cy", "显式中心 y（墙脚/床脚所在层）", -64, 320)
                .optionalInteger("cz", "显式中心 z", -30000000, 30000000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_trade: not on a server level").toJson());
                return;
            }
            String name = args.has("name") && !args.get("name").getAsString().isBlank()
                    ? sanitize(args.get("name").getAsString()) : "trade_post";
            BlockPos center = (args.has("cx") && args.has("cy") && args.has("cz"))
                    ? new BlockPos(args.get("cx").getAsInt(), args.get("cy").getAsInt(), args.get("cz").getAsInt())
                    : self.blockPosition();
            int half = SIZE / 2;
            int minX = center.getX() - half;
            int minZ = center.getZ() - half;
            int anchorY = center.getY();

            CompoundTag tag = buildTag();
            int fences = 46, gate = 1, beds = 4;

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
            data.put("size", SIZE + "x" + SIZE + " wall=2");
            data.put("fence", FENCE + " x" + fences);
            data.put("gate", GATE + " x1 (north)");
            data.put("beds", BED + " x" + beds);
            data.put("cells", 46 + 1 + 4);
            data.put("anchor", minX + "," + anchorY + "," + minZ);
            data.put("next", "blueprint action=build file=" + name
                    + " x=" + minX + " y=" + anchorY + " z=" + minZ);
            reply.accept(TaskResult.ok("trade post blueprint '" + name + "' (7x7, 2-high fence + gate + 4 beds) "
                    + "written to schematics/", data).toJson());
        } catch (IOException | RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_trade error: " + ex.getMessage()).toJson());
        }
    }

    /**
     * 稀疏蓝图，palette：0=栅栏，1=栅栏门(facing north)，2=床(facing north)。
     *
     * <p>围墙两层（y=0/y=1）环 24 格 ×2 = 48；北中 (3,0) 下层换门、上层<b>留空</b>——
     * 门上方不留空的话，门开了上面还压一栅栏，人和羊都过不去（栅栏不挡门的上沿会卡碰撞）。
     * 床脚放在 (2,3)/(4,3)/(2,5)/(4,5)（全朝北，床头分别落 z=2/z=4，互不冲突）。
     */
    private static CompoundTag buildTag() {
        CompoundTag root = new CompoundTag();
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(SIZE));
        sizeTag.add(IntTag.valueOf(2));
        sizeTag.add(IntTag.valueOf(SIZE));
        root.put("size", sizeTag);

        ListTag palette = new ListTag();
        palette.add(nameTag(FENCE));
        palette.add(propTag(GATE, "facing", "north"));
        palette.add(propTag(BED, "facing", "north"));
        root.put("palette", palette);

        ListTag blocks = new ListTag();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (!onPerimeter(x, z)) continue;
                boolean gateCol = (x == SIZE / 2 && z == 0);
                if (gateCol) {
                    blocks.add(cellTag(x, 0, z, 1));        // 下层：门
                    continue;                                 // 上层：留空
                }
                blocks.add(cellTag(x, 0, z, 0));
                blocks.add(cellTag(x, 1, z, 0));
            }
        }
        blocks.add(cellTag(2, 0, 3, 2));
        blocks.add(cellTag(4, 0, 3, 2));
        blocks.add(cellTag(2, 0, 5, 2));
        blocks.add(cellTag(4, 0, 5, 2));
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static boolean onPerimeter(int x, int z) {
        return x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
    }

    private static CompoundTag nameTag(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    private static CompoundTag propTag(String name, String key, String value) {
        CompoundTag t = nameTag(name);
        CompoundTag props = new CompoundTag();
        props.putString(key, value);
        t.put("Properties", props);
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

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
        return s.isBlank() ? "trade_post" : s;
    }
}
