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
 * 生成"火柴盒核心屋"蓝图：7×7 外框、墙高 2、平顶、一个橡木门，屋里放
 * 床 + 箱子 + 工作台 + 熔炉。作为基地的第一块（核心），建成后把中心登记成基地基准点。
 *
 * <p>放到 {@code schematics/}，用 {@code blueprint action=build} 施工。墙/顶/家具都是普通方块，
 * 蓝图能直接建（无液体格）。这是模块化基地的"种子格"，之后在旁边逐格贴别的设施。
 */
public final class SettlementHouseTool implements NumenTool {

    private static final int SIZE = 7;
    private static final int WALL_H = 2;
    private static final String PLANKS = "minecraft:oak_planks";

    public SettlementHouseTool() {}

    @Override public String name() { return "settlement_house"; }

    @Override public String description() {
        return "生成一个 7×7 火柴盒核心屋蓝图（墙高 2、平顶、南面一个橡木门，里面放床+箱子+工作台+熔炉）。"
                + "作为基地的第一块核心。图写到 schematics/，随后用 blueprint action=build "
                + "file=<返回的 blueprint> x/y/z=<返回的 anchor> 施工（x/y/z 原样用返回的 anchor）。"
                + "建成后用 settlement_base 以它的中心登记基地基准点。生存需要 oak_planks×约144 + 门/床/"
                + "工作台/熔炉/箱子各1。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("name", "蓝图名（不含扩展名），默认核心屋 core_house")
                // 统一放置入口按格算好坐标后显式传入，不以站位为中心。
                .optionalInteger("cx", "显式中心 x（不给则用你当前站位）", -30000000, 30000000)
                .optionalInteger("cy", "显式中心 y（室内地板层）", -64, 320)
                .optionalInteger("cz", "显式中心 z", -30000000, 30000000)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            if (self == null || self.getServer() == null || self.serverLevel() == null) {
                reply.accept(TaskResult.fail("settlement_house: not on a server level").toJson());
                return;
            }
            String name = args.has("name") && !args.get("name").getAsString().isBlank()
                    ? sanitize(args.get("name").getAsString()) : "core_house";

            BlockPos center = (args.has("cx") && args.has("cy") && args.has("cz"))
                    ? new BlockPos(args.get("cx").getAsInt(), args.get("cy").getAsInt(), args.get("cz").getAsInt())
                    : self.blockPosition();
            int half = SIZE / 2;                 // 3
            int minX = center.getX() - half;
            int minZ = center.getZ() - half;
            int floorY = center.getY();          // 脚所在层当室内地板

            CompoundTag tag = buildTag();

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
            data.put("size", SIZE + "x" + SIZE + " wall=" + WALL_H);
            data.put("cells", buildableCells());
            data.put("anchor", minX + "," + floorY + "," + minZ);
            data.put("center", (minX + half) + "," + floorY + "," + (minZ + half));
            data.put("next", "blueprint action=build file=" + name
                    + " x=" + minX + " y=" + floorY + " z=" + minZ);
            reply.accept(TaskResult.ok("core house blueprint '" + name + "' written to schematics/", data).toJson());
        } catch (IOException | RuntimeException ex) {
            reply.accept(TaskResult.fail("settlement_house error: " + ex.getMessage()).toJson());
        }
    }

    /**
     * 7×7×4：y=0 地板、y=1..2 墙(南中留门)、y=3 平顶；室内 y=1..2 清空；
     * 家具在 y=1：工作台(1,1,1)、熔炉(5,1,1)、箱子(1,1,5)、床(5,1,5)。
     * 门只写下半格(南中 x=3,z=0,y=1)，上半格由放置器 setPlacedBy 自动生成。
     */
    private static CompoundTag buildTag() {
        CompoundTag root = new CompoundTag();
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(SIZE));
        sizeTag.add(IntTag.valueOf(WALL_H + 2));   // y=0 地板 + 2 墙 + 1 顶
        sizeTag.add(IntTag.valueOf(SIZE));
        root.put("size", sizeTag);

        ListTag palette = new ListTag();
        palette.add(nameTag(PLANKS));               // 0
        palette.add(nameTag("minecraft:air"));      // 1
        palette.add(nameTag("minecraft:oak_door")); // 2
        palette.add(nameTag("minecraft:crafting_table")); // 3
        palette.add(nameTag("minecraft:furnace"));  // 4
        palette.add(nameTag("minecraft:chest"));    // 5
        palette.add(nameTag("minecraft:white_bed"));// 6
        root.put("palette", palette);

        int gateX = SIZE / 2;   // 3，南面中间
        ListTag blocks = new ListTag();
        // 地板 + 顶
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                blocks.add(cellTag(x, 0, z, 0));
                blocks.add(cellTag(x, WALL_H + 1, z, 0));
            }
        }
        // 墙（南中留门）
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (!onPerimeter(x, z)) continue;
                for (int y = 1; y <= WALL_H; y++) {
                    if (z == 0 && x == gateX) continue;   // 门洞
                    blocks.add(cellTag(x, y, z, 0));
                }
            }
        }
        // 门下半格（上半格由 setPlacedBy 生成）
        blocks.add(cellTag(gateX, 1, 0, 2));
        // 室内清空
        for (int x = 1; x < SIZE - 1; x++) {
            for (int z = 1; z < SIZE - 1; z++) {
                for (int y = 1; y <= WALL_H; y++) {
                    blocks.add(cellTag(x, y, z, 1));
                }
            }
        }
        // 家具（放最后，覆盖室内空格）
        blocks.add(cellTag(1, 1, 1, 3));               // 工作台
        blocks.add(cellTag(SIZE - 2, 1, 1, 4));        // 熔炉
        blocks.add(cellTag(1, 1, SIZE - 2, 5));        // 箱子
        blocks.add(cellTag(SIZE - 2, 1, SIZE - 2, 6)); // 床
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static boolean onPerimeter(int x, int z) {
        return x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
    }

    /**
     * 图纸实际写出的格数（施工账的 total）。
     *
     * <p>与 {@link #buildTag()} 同一套循环口径：地板 + 顶（各 49）、墙（周长 24 × 墙高 2
     * − 门洞 2）、室内清空（25×2）、门下半格 1、家具 4。清空格也算"交代过"的格。
     */
    private static int buildableCells() {
        int n = 0;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                n += 2;                                   // 地板 + 顶
                if (!onPerimeter(x, z)) {
                    n += WALL_H;                          // 室内清空
                    continue;
                }
                for (int y = 1; y <= WALL_H; y++) {
                    if (!(z == 0 && x == SIZE / 2)) n++;  // 墙（门洞除外）
                }
            }
        }
        n += 1;                                           // 门下半格
        n += 4;                                           // 家具
        return n;
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

    private static String sanitize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
        return s.isBlank() ? "core_house" : s;
    }
}
