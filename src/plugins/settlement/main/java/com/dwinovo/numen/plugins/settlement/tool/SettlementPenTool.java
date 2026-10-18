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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 生成一个<b>模块化牧场蓝图</b>（默认 5×5 羊圈）：栅栏围一圈，留一个门口位放<b>地毯</b>。
 *
 * <p>为什么门口用地毯：用户实测——栅栏圈留一格开口、那格放地毯，同伴（走我们的寻路）
 * 能从上面进出，动物不怎么过这道。比栅栏门省事（不用开关），也符合"模块化小图"。
 *
 * <p>图和平台一样写到 {@code schematics/}，再用 {@code blueprint action=build} 施工。
 * 蓝图是稀疏的（只有栅栏和地毯，不动地面）。栅栏放在脚所在层（anchor 的 y）。
 */
public final class SettlementPenTool implements NumenTool {

    private static final int MIN_SIZE = 3;
    private static final int MAX_SIZE = 15;
    private static final String FENCE = "minecraft:oak_fence";
    private static final String GATE = "minecraft:oak_fence_gate";

    public SettlementPenTool() {}

    @Override public String name() { return "settlement_pen"; }

    @Override public String description() {
        return "生成一个模块化牧场蓝图（默认 5×5）：栅栏围整圈，其中一格换成栅栏门（默认北边中间）。"
                + "animal=sheep|cow 只影响命名；gate_side=north|south|east|west 选门留在哪条边；"
                + "cx/cy/cz 可显式指定中心（统一放置入口按格算好坐标后会这么传）。"
                + "图写到 schematics/，随后用 blueprint action=build file=<返回的 blueprint> "
                + "x/y/z=<返回的 anchor> 施工（x/y/z 原样用返回的 anchor）。"
                + "生存下需要栅栏×15 + 栅栏门×1；不够就先备料。"
                + "★ 不再用地毯当门：实测地毯+栅栏对假玩家没有可站面，TP 上去会落回栅栏层。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("animal", "牧场动物，默认 sheep", "sheep", "cow")
                .optionalInteger("size", "正方形边长，默认 5（奇数）", MIN_SIZE, MAX_SIZE)
                .optionalString("name", "蓝图名（不含扩展名），默认 pen_<animal>")
                // 统一放置入口要按"格"精确落位，不能以同伴站位为中心——这里允许显式给中心。
                .optionalInteger("cx", "显式中心 x（不给则用你当前站位）", -30000000, 30000000)
                .optionalInteger("cy", "显式中心 y（栅栏所在层）", -64, 320)
                .optionalInteger("cz", "显式中心 z", -30000000, 30000000)
                .optionalEnum("gate_side", "门留在哪条边，默认 north", "north", "south", "east", "west")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_pen: not on a server level").toJson());
                return;
            }
            String animal = args.has("animal") ? args.get("animal").getAsString().toLowerCase(Locale.ROOT) : "sheep";
            int size = clamp(args.has("size") ? args.get("size").getAsInt() : 5, MIN_SIZE, MAX_SIZE);
            if (size % 2 == 0) size += 1;   // 保持能以同伴为中心
            String name = args.has("name") && !args.get("name").getAsString().isBlank()
                    ? sanitize(args.get("name").getAsString()) : ("pen_" + sanitize(animal));
            String gateSide = args.has("gate_side")
                    ? args.get("gate_side").getAsString().toLowerCase(Locale.ROOT) : "north";

            // 显式中心优先（统一放置入口按格算好坐标后传进来），否则退回站位。
            BlockPos center = (args.has("cx") && args.has("cy") && args.has("cz"))
                    ? new BlockPos(args.get("cx").getAsInt(), args.get("cy").getAsInt(), args.get("cz").getAsInt())
                    : self.blockPosition();
            int half = size / 2;
            int minX = center.getX() - half;
            int minZ = center.getZ() - half;
            int anchorY = center.getY();   // 栅栏/门放在脚所在层

            CompoundTag tag = buildTag(size, gateSide);
            int fences = perimeterCells(size) - 1;

            MinecraftServer server = self.getServer();
            Path dir = server.getServerDirectory().resolve("schematics");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".nbt");
            Files.deleteIfExists(dir.resolve(name + ".snbt"));
            Files.deleteIfExists(file);
            NbtIo.writeCompressed(tag, file);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("blueprint", name);
            data.put("animal", animal);
            data.put("file", file.toString());
            data.put("size", size + "x" + size);
            data.put("fence", FENCE + " x" + fences);
            data.put("gate", GATE + " x1 (" + gateSide + ")");
            data.put("gate_side", gateSide);
            data.put("cells", perimeterCells(size));   // 图纸格数：施工账的 total 用它
            data.put("anchor", minX + "," + anchorY + "," + minZ);
            data.put("next", "blueprint action=build file=" + name
                    + " x=" + minX + " y=" + anchorY + " z=" + minZ);
            reply.accept(TaskResult.ok("pen blueprint '" + name + "' (" + size + "x" + size
                    + ", fence ring + fence gate on " + gateSide + ") written to schematics/", data).toJson());
        } catch (IOException | RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_pen error: " + ex.getMessage()).toJson());
        }
    }

    private static int perimeterCells(int size) {
        return size * 4 - 4;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
        return s.isBlank() ? "pen" : s;
    }

    /**
     * 稀疏蓝图：palette[0]=栅栏, palette[1]=栅栏门。y=0 是整圈栅栏，其中门口那一格换成
     * 栅栏门（带 {@code facing} 属性，关着）。地面/天空不动。
     *
     * <p><b>为什么不用地毯门</b>（2026-10-09 实机改）：旧版把地毯压在栅栏顶上当门，
     * 依据是"人跳得过、羊跳不过"。但实测我们的假玩家实体<b>站不住</b>——
     * 栅栏+地毯的组合没有可站面，TP 上去会落回栅栏层。所以默认入口改<b>栅栏门</b>：
     * 可开可关、人畜都按门走，也是 MC 里牧场的标准做法。
     *
     * <p>门的 {@code facing} 指它"朝向哪边"，与门的朝向轴对应：
     * 北/南边上的门沿 X 轴排（facing north/south），东/西边上的门沿 Z 轴排（facing east/west）。
     */
    private static CompoundTag buildTag(int size, String gateSide) {
        CompoundTag root = new CompoundTag();
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(size));
        sizeTag.add(IntTag.valueOf(2));
        sizeTag.add(IntTag.valueOf(size));
        root.put("size", sizeTag);

        ListTag palette = new ListTag();
        palette.add(nameTag(FENCE));
        palette.add(gateTag(gateSide));
        root.put("palette", palette);

        int[] gate = gateCell(size, gateSide);
        ListTag blocks = new ListTag();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (!onPerimeter(x, z, size)) continue;
                if (x == gate[0] && z == gate[1]) continue;   // 门口那格留给门
                blocks.add(cellTag(x, 0, z, 0));              // 栅栏
            }
        }
        blocks.add(cellTag(gate[0], 0, gate[1], 1));          // 栅栏门
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    /** 门口格：各边中点（size 为奇数，保证中点唯一）。 */
    private static int[] gateCell(int size, String gateSide) {
        int mid = size / 2;
        return switch (gateSide) {
            case "south" -> new int[]{mid, size - 1};
            case "east" -> new int[]{size - 1, mid};
            case "west" -> new int[]{0, mid};
            default -> new int[]{mid, 0};   // north
        };
    }

    private static boolean onPerimeter(int x, int z, int size) {
        return x == 0 || z == 0 || x == size - 1 || z == size - 1;
    }

    private static CompoundTag nameTag(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    /** 栅栏门带 facing 属性；不给属性的话放置器会按她的视线乱转，门轴方向就不对了。 */
    private static CompoundTag gateTag(String gateSide) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", GATE);
        CompoundTag props = new CompoundTag();
        props.putString("facing", gateSide);
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
}
