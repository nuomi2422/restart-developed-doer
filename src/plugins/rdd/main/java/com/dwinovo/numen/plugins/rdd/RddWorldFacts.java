package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.FunctionalBlockTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

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
                case "biome" -> biome(ap, condition);
                case "block_nearby" -> blockNearby(ap, condition);
                case "container_nearby" -> containerNearby(ap, condition);
                case "block_mined" -> statBlockMined(ap, condition);
                case "item_crafted" -> statItem(ap, Stats.ITEM_CRAFTED, condition);
                case "item_used" -> statItem(ap, Stats.ITEM_USED, condition);
                case "item_picked_up" -> statItem(ap, Stats.ITEM_PICKED_UP, condition);
                case "dimension" -> dimensionReached(ap, condition);
                case "y_below" -> yBelow(ap, condition);
                case "position_at" -> positionAt(ap, condition);
                case "entity_nearby" -> entityNearby(ap, condition);
                case "block_at" -> blockAt(ap, condition);
                case "container_at" -> containerAt(ap, condition);
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
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("structure");
        // 先剥 # 再解析：#minecraft:village 直接喂给 ResourceLocation.tryParse 会炸。
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        BlockPos pos = ap.blockPosition();
        var here = level.structureManager().getAllStructuresAt(pos);

        // #tag：原版里「一个东西好几个变体」是常态（五个村庄 id、冷/暖两种海底废墟…）。
        // 规划器逐个枚举变体必漏，漏一个就静默判假 → 所以 tag 与精确 id 同等支持。
        if (WorldFactConditions.isTag(raw)) {
            var set = registry.get(TagKey.create(Registries.STRUCTURE, key));
            if (set.isEmpty()) return false;
            Set<Structure> members = new HashSet<>();
            set.get().forEach(holder -> members.add(holder.value()));
            for (Structure found : here.keySet()) {
                if (members.contains(found)) return true;
            }
            return false;
        }

        var holder = registry.get(ResourceKey.create(Registries.STRUCTURE, key));
        if (holder.isEmpty()) return false;
        var wanted = holder.get().value();
        if (here.containsKey(wanted)) return true;
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

    /** 站在什么群系里。原版「任何森林/任何海洋/任何山地」都是 biome tag，不是具体 id。 */
    private static boolean biome(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("biome");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null || !dimension(level, condition)) return false;
        Holder<Biome> here = level.getBiome(ap.blockPosition());
        if (WorldFactConditions.isTag(raw)) {
            // 「任何森林/任何海洋/任何山地」在原版是 biome tag，不是具体 id。
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, key);
            var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
            if (registry.get(tag).isEmpty()) return false;
            return here.is(tag);
        }
        return key.equals(here.unwrapKey().map(k -> k.location()).orElse(null));
    }

    /** 附近有没有某类方块。矿石 / 刷怪笼 / 干草捆 都不带方块实体，观察器看不见它们，这里能。 */
    private static boolean blockNearby(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("block");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null || !dimension(level, condition)) return false;
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_BLOCK_RADIUS);
        Predicate<BlockState> hit;
        if (WorldFactConditions.isTag(raw)) {
            // 直接问状态本身「你在不在这个 tag 里」，不自己遍历注册表成员 ——
            // BlockState#is(TagKey) 是原版原生判定，#minecraft:ores / #minecraft:logs 都能用。
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, key);
            hit = state -> state.is(tag);
        } else {
            var wanted = BuiltInRegistries.BLOCK.get(key);
            if (wanted == null || !BuiltInRegistries.BLOCK.containsKey(key)) return false;
            hit = state -> state.is(wanted);
        }
        return scanNeighbourhood(level, ap.blockPosition(), radius, hit);
    }

    /**
     * 附近容器里有没有某物 —— 就是 voyager 的 chest_memory：观测「那边有个箱子，里面有干草块」，
     * 摆到执行 AI 眼前让它自己决定要不要去拿。只读不派工，不改规划。
     */
    private static boolean containerNearby(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("item");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null || !dimension(level, condition)) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_CONTAINER_RADIUS);
        Predicate<ItemStack> hit;
        if (WorldFactConditions.isTag(raw)) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, key);
            hit = stack -> stack.is(tag);
        } else {
            var wanted = BuiltInRegistries.ITEM.get(key);
            if (wanted == null || !BuiltInRegistries.ITEM.containsKey(key)) return false;
            hit = stack -> stack.is(wanted);
        }
        return countInNeighbourhood(level, ap.blockPosition(), radius, hit, minimum) >= minimum;
    }

    /**
     * 有界邻域扫描：只读已加载 chunk，绝不触发生成。判定每秒跑一次，所以半径被
     * {@link WorldFactConditions#MAX_NEARBY_RADIUS} 硬顶住。
     */
    private static boolean scanNeighbourhood(ServerLevel level, BlockPos origin, int radius,
                                             Predicate<BlockState> hit) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int squared = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > squared || planar + dy * dy > squared) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (hit.test(level.getBlockState(cursor))) return true;
                }
            }
        }
        return false;
    }

    private static int countInNeighbourhood(ServerLevel level, BlockPos origin, int radius,
                                            Predicate<ItemStack> hit, int stopAt) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int squared = radius * radius;
        int total = 0;
        for (int dx = -radius; dx <= radius && total < stopAt; dx++) {
            for (int dy = -radius; dy <= radius && total < stopAt; dy++) {
                for (int dz = -radius; dz <= radius && total < stopAt; dz++) {
                    int planar = dx * dx + dz * dz;
                    if (planar > squared || planar + dy * dy > squared) continue;
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (!(level.getBlockEntity(cursor) instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize() && total < stopAt; slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (stack.isEmpty()) continue;
                        if (hit.test(stack)) total += stack.getCount();
                    }
                }
            }
        }
        return total;
    }

    // ---- A 组：原版 Stats（判“真的做了 N 次”，不是“此刻背包里有”） ----

    private static boolean statBlockMined(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("block");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        return sumStat(ap, Stats.BLOCK_MINED, BuiltInRegistries.BLOCK, key,
                WorldFactConditions.isTag(raw)) >= minimum;
    }

    private static boolean statItem(NumenPlayer ap, StatType<Item> stat, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("item");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        return sumStat(ap, stat, BuiltInRegistries.ITEM, key,
                WorldFactConditions.isTag(raw)) >= minimum;
    }

    /** 汇总某注册表对象（或整个 #tag 成员）在该统计类型上的终身值。 */
    private static <T> int sumStat(NumenPlayer ap, StatType<T> stat, Registry<T> registry,
                                   ResourceLocation key, boolean isTag) {
        int total = 0;
        if (isTag) {
            var members = registry.getTag(TagKey.create(registry.key(), key));
            if (members.isEmpty()) return 0;
            for (Holder<T> holder : members.get()) {
                total += statValue(ap, stat, holder.value());
            }
        } else {
            T value = registry.get(key);
            if (value == null || !registry.containsKey(key)) return 0;
            total += statValue(ap, stat, value);
        }
        return total;
    }

    private static <T> int statValue(NumenPlayer ap, StatType<T> stat, T key) {
        try {
            return ap.getStats().getValue(stat, key);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    // ---- B 组：位置 / 维度 ----

    private static boolean dimensionReached(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation wanted = id(condition, "dimension");
        return wanted != null && ap.serverLevel().dimension().location().equals(wanted);
    }

    private static boolean yBelow(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("y");
        if (!(raw instanceof Number number)) return false;
        return ap.getY() <= number.doubleValue();
    }

    private static boolean positionAt(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        BlockPos pos = coordOf(condition);
        if (pos == null) return false;
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_POSITION_RADIUS);
        BlockPos me = ap.blockPosition();
        long dx = (long) me.getX() - pos.getX();
        long dy = (long) me.getY() - pos.getY();
        long dz = (long) me.getZ() - pos.getZ();
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    private static BlockPos coordOf(Map<String, Object> condition) {
        Object x = condition.get("x");
        Object y = condition.get("y");
        Object z = condition.get("z");
        if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz
                && nx.doubleValue() == nx.intValue() && ny.doubleValue() == ny.intValue()
                && nz.doubleValue() == nz.intValue()) {
            return new BlockPos(nx.intValue(), ny.intValue(), nz.intValue());
        }
        return null;
    }

    // ---- C 组：实体 / 精确坐标 ----

    private static boolean entityNearby(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        Object raw = condition.get("entity");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        int radius = WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_BLOCK_RADIUS);
        boolean isTag = WorldFactConditions.isTag(raw);
        Set<EntityType<?>> tagMembers = null;
        EntityType<?> exact = null;
        if (isTag) {
            var set = BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, key));
            if (set.isEmpty()) return false;
            tagMembers = new HashSet<>();
            for (Holder<EntityType<?>> holder : set.get()) tagMembers.add(holder.value());
        } else {
            exact = BuiltInRegistries.ENTITY_TYPE.get(key);
            if (exact == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return false;
        }
        final Set<EntityType<?>> members = tagMembers;
        final EntityType<?> exactType = exact;
        var box = ap.getBoundingBox().inflate(radius);
        for (Entity entity : level.getEntities(ap, box, e -> e != ap && e.isAlive())) {
            if (isTag ? members.contains(entity.getType()) : entity.getType() == exactType) {
                return true;
            }
        }
        return false;
    }

    private static boolean blockAt(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        BlockPos pos = coordOf(condition);
        if (pos == null || !level.hasChunkAt(pos)) return false;
        Object raw = condition.get("block");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        BlockState state = level.getBlockState(pos);
        if (WorldFactConditions.isTag(raw)) {
            return state.is(TagKey.create(Registries.BLOCK, key));
        }
        Block wanted = BuiltInRegistries.BLOCK.get(key);
        return wanted != null && BuiltInRegistries.BLOCK.containsKey(key) && state.is(wanted);
    }

    private static boolean containerAt(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        if (!dimension(level, condition)) return false;
        BlockPos pos = coordOf(condition);
        if (pos == null || !level.hasChunkAt(pos)) return false;
        if (!(level.getBlockEntity(pos) instanceof Container container)) return false;
        Object raw = condition.get("item");
        ResourceLocation key = ResourceLocation.tryParse(WorldFactConditions.tagId(raw));
        if (key == null) return false;
        Predicate<ItemStack> hit = itemHit(raw, key);
        if (hit == null) return false;
        int minimum = WorldFactConditions.minimumOf(condition, 1);
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize() && total < minimum; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && hit.test(stack)) total += stack.getCount();
        }
        return total >= minimum;
    }

    /** 物品匹配谓词（精确 id 或 #tag）；非法返回 null。 */
    private static Predicate<ItemStack> itemHit(Object raw, ResourceLocation key) {
        if (WorldFactConditions.isTag(raw)) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, key);
            return stack -> stack.is(tag);
        }
        Item wanted = BuiltInRegistries.ITEM.get(key);
        if (wanted == null || !BuiltInRegistries.ITEM.containsKey(key)) return null;
        return stack -> stack.is(wanted);
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
        // 未绑床时 getRespawnPosition() 可能返回 BlockPos.ZERO（哨兵）而非 null——
        // 必须显式挡掉，否则会拿 (0,0,0) 当"重生床"去查（语义错，且恰好在原点有床时会误判成功）。
        if (spawn == null || spawn.equals(BlockPos.ZERO)) {
            return null;
        }
        ServerLevel level = ap.getServer().getLevel(ap.getRespawnDimension());
        if (level == null || !dimension(level, condition) || !BedBlock.canSetSpawn(level)) {
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
        // 2026-10-01 修（实机抓到的真死锁，用户第 5 轮实机观察）：
        // 原 complete = chest && furnace（床周围 8 格内必须同时有箱子+熔炉）。
        // 实机后果：规划器把 type=base 排成**第一个**二级 → 判据要求"基地已建成"
        // → 但造箱子/熔炉的二级排在它**后面** → base 永远不满足 → 第一个二级永远 STALLED
        // → 后面 5 个二级永远 PENDING → 整条链停摆（RL-2「依赖门不得死锁」的另一种形态：
        // 不是 WAITING 卡住，是**第一个目标自身不可达**）。
        //
        // 改法：让 complete 与本方法自己的注释一致 —— **一张已验证可用的重生床就是基地**
        //（上面已经把床本身完整校验过：双格齐全、朝向一致、旁边站得下人）。
        // 箱子/熔炉不再当硬门，但**继续扫、继续报**：缺了就写进 facilities 说明，
        // 让监测台能看出"基地还很空"，而不是只看到一个布尔值。
        //
        // 治本项另记（A2）：规划器不该把 base 排成第一个二级 —— 那是排期约束问题，
        // 要改规划器，下一批做。
        if (!chest || !furnace) {
            StringBuilder missing = new StringBuilder();
            if (!chest) missing.append("no chest within 8 blocks of the respawn bed");
            if (!furnace) {
                if (missing.length() > 0) missing.append("; ");
                missing.append("no furnace within 8 blocks of the respawn bed");
            }
            facilities.add("(missing) " + missing);
        }
        boolean complete = true;
        return new BaseSnapshot(level.dimension().location().toString(), spawn.immutable(),
                complete, List.copyOf(facilities), Map.copyOf(storedItems));
    }

    record BaseSnapshot(String dimension, BlockPos spawn, boolean complete,
                        List<String> facilities, Map<String, Integer> storedItems) {}
}
