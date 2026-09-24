package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.FunctionalBlockTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only, server-thread world evidence. Unknown/unloaded facts never pass. */
public final class RddWorldFacts {
    private RddWorldFacts() {}

    /** Caller must be on the server thread; this method never schedules work or loads chunks. */
    public static boolean matches(NumenPlayer ap, Map<String, Object> condition) {
        if (ap == null || condition == null || ap.getServer() == null
                || !ap.getServer().isSameThread()) return false;
        try {
            return switch (String.valueOf(condition.get("type"))) {
                case "advancement" -> advancement(ap, condition);
                case "entity_killed" -> killed(ap, condition);
                case "structure" -> structure(ap, condition);
                case "base" -> base(ap, condition);
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            // Malformed IDs/conditions are unproven, never inventory fallbacks.
            return false;
        }
    }

    private static ResourceLocation id(Map<String, Object> condition, String key) {
        Object raw = condition.get(key);
        if (!(raw instanceof String value) || !value.contains(":")) return null;
        return ResourceLocation.tryParse(value);
    }

    private static boolean dimension(ServerLevel level, Map<String, Object> condition) {
        if (!condition.containsKey("dimension")) return true;
        ResourceLocation wanted = id(condition, "dimension");
        return wanted != null && level.dimension().location().equals(wanted);
    }

    private static boolean advancement(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation key = id(condition, "advancement");
        if (key == null) return false;
        var advancement = ap.getServer().getAdvancements().get(key);
        return advancement != null && ap.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static boolean killed(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation key = id(condition, "entity");
        Object threshold = condition.getOrDefault("minimum", 1);
        if (!(threshold instanceof Number number)) return false;
        double minimum = number.doubleValue();
        if (!Double.isFinite(minimum) || minimum < 1 || minimum > Integer.MAX_VALUE
                || minimum != Math.rint(minimum) || key == null
                || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return false;
        // Lifetime kills credited to THIS companion, not disappearance or the owner's stats.
        return ap.getStats().getValue(Stats.ENTITY_KILLED,
                BuiltInRegistries.ENTITY_TYPE.get(key)) >= minimum;
    }

    private static boolean structure(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        ResourceLocation key = id(condition, "structure");
        if (key == null || !dimension(level, condition)) return false;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        if (!registry.containsKey(key)) return false;
        var wanted = registry.get(key);
        BlockPos pos = ap.blockPosition();
        LevelChunk current = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (current == null) return false;
        if (contains(current.getStartForStructure(wanted), pos)) return true;
        var references = current.getAllReferences().get(wanted);
        if (references == null) return false;
        // StructureManager#getStructureAt may load start chunks. Resolve only already-loaded starts.
        for (long reference : references) {
            LevelChunk origin = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(reference), ChunkPos.getZ(reference));
            if (origin != null && contains(origin.getStartForStructure(wanted), pos)) return true;
        }
        return false;
    }

    private static boolean contains(StructureStart start, BlockPos pos) {
        return start != null && start.isValid() && start.getBoundingBox().isInside(pos);
    }

    private static boolean base(NumenPlayer ap, Map<String, Object> condition) {
        BaseSnapshot snapshot = inspectBase(ap, condition);
        return snapshot != null && snapshot.complete();
    }

    /** A valid personal respawn bed is a reusable base asset even before every facility is complete. */
    static BaseSnapshot inspectBase(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos spawn = ap.getRespawnPosition();
        ServerLevel level = ap.getServer().getLevel(ap.getRespawnDimension());
        if (spawn == null || level == null || !dimension(level, condition) || !BedBlock.canSetSpawn(level)) {
            return null;
        }
        // A tiny bounded area, preflighted before any block/collision query. No chunk generation.
        for (int x = (spawn.getX() - 8) >> 4; x <= (spawn.getX() + 8) >> 4; x++) {
            for (int z = (spawn.getZ() - 8) >> 4; z <= (spawn.getZ() + 8) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) return null;
            }
        }
        var bed = level.getBlockState(spawn);
        if (!(bed.getBlock() instanceof BedBlock)) return null;
        var facing = bed.getValue(BedBlock.FACING);
        BlockPos otherPos = spawn.relative(bed.getValue(BedBlock.PART) == BedPart.HEAD
                ? facing.getOpposite() : facing);
        var other = level.getBlockState(otherPos);
        if (!other.is(bed.getBlock()) || other.getValue(BedBlock.PART) == bed.getValue(BedBlock.PART)
                || other.getValue(BedBlock.FACING) != facing
                || BedBlock.findStandUpPosition(ap.getType(), level, spawn, facing, ap.getRespawnAngle()).isEmpty()) {
            return null;
        }
        boolean chest = false;
        boolean furnace = false;
        List<String> facilities = new ArrayList<>();
        Map<String, Integer> storedItems = new TreeMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(spawn.offset(-8, -4, -8), spawn.offset(8, 4, 8))) {
            var block = level.getBlockState(pos);
            chest |= block.is(Blocks.CHEST) || block.is(Blocks.TRAPPED_CHEST);
            furnace |= block.is(Blocks.FURNACE);
            String blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            if (FunctionalBlockTypes.isTracked(blockId)) {
                facilities.add(blockId + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
            if (level.getBlockEntity(pos) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        storedItems.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        return new BaseSnapshot(level.dimension().location().toString(), spawn.immutable(),
                chest && furnace, List.copyOf(facilities), Map.copyOf(storedItems));
    }

    record BaseSnapshot(String dimension, BlockPos spawn, boolean complete,
                        List<String> facilities, Map<String, Integer> storedItems) {}
}
