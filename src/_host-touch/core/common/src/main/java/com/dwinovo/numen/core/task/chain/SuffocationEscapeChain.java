package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.act.ToolSelect;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Breaks the block intersecting the body when vanilla reports that the
 * companion is suffocating in a wall. The target is restricted to a breakable,
 * non-protected collision shape already intersecting the body; it never digs a
 * speculative route or a nearby facility block.
 */
public final class SuffocationEscapeChain implements Task, Reflex {

    private BlockDigger digger;
    private boolean blockedNoted;

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (!companion.fcEnabled()) {
            clear(companion);
            return false;
        }
        return companion.isInWall();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);

        BlockPos target = escapeBlock(
                companion.level(), companion.getBoundingBox(), companion.getEyePosition());
        if (target == null) {
            if (!blockedNoted) {
                blockedNoted = true;
                com.dwinovo.numen.event.NumenEvents.body(companion,
                        "suffocating in a protected or unbreakable block; FC could not choose a safe block to dig");
            }
            InputDriver.jump(companion);
            return TaskState.RUNNING;
        }

        blockedNoted = false;
        if (digger == null) {
            digger = new BlockDigger(companion);
        }
        BlockState state = companion.level().getBlockState(target);
        ToolSelect.holdBestTool(companion, state);
        BlockHitResult insideHit = new BlockHitResult(
                Vec3.atCenterOf(target), Direction.UP, target, true);
        BlockDigger.DigResult result = digger.digStep(insideHit);
        if (result == BlockDigger.DigResult.BROKE_TARGET) {
            com.dwinovo.numen.event.NumenEvents.body(companion,
                    "broke suffocating block " + target + " and opened breathing room");
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        clear(companion);
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
    }

    @Override
    public String name() {
        return "suffocation_escape";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "身体被方块卡住并开始窒息时会暂停当前任务,挖掉与身体重叠的安全可破坏方块";
    }

    private void clear(NumenPlayer companion) {
        if (digger != null) {
            digger.cancel();
            digger = null;
        }
        blockedNoted = false;
    }

    static BlockPos escapeBlock(BlockGetter level, AABB body, Vec3 eye) {
        int minX = (int) Math.floor(body.minX + 1.0e-7);
        int minY = (int) Math.floor(body.minY + 1.0e-7);
        int minZ = (int) Math.floor(body.minZ + 1.0e-7);
        int maxX = (int) Math.floor(body.maxX - 1.0e-7);
        int maxY = (int) Math.floor(body.maxY - 1.0e-7);
        int maxZ = (int) Math.floor(body.maxZ - 1.0e-7);

        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isSuffocating(level, pos)
                            || !BlockHelper.isBreakable(level, pos)
                            || BlockHelper.shouldAvoidBreaking(level, pos)) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, pos);
                    if (shape.isEmpty() || !shape.bounds().move(pos).intersects(body)) continue;
                    double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }
}
