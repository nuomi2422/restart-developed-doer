package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Emergency lava egress. Navigation refuses lava when planning, but a body can
 * still be pushed or fall into it while an explicit task is running. This
 * reflex swims upward every tick and steers toward the nearest dry two-block
 * foothold; when none is visible it only rises instead of choosing a random
 * heading deeper into the pool.
 */
public final class LavaEscapeChain implements Task, Reflex {

    static final int EXIT_RADIUS = 5;

    private boolean episodeActive;
    private boolean noExitNoted;

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (!companion.fcEnabled()) {
            reset();
            return false;
        }
        boolean inLava = companion.isInLava();
        if (!inLava && episodeActive) {
            com.dwinovo.numen.event.NumenEvents.body(companion,
                    "escaped lava using the automatic FC survival reflex");
            reset();
        }
        return inLava;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        episodeActive = true;
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);

        BlockPos exit = nearestDryFoothold(
                companion.level(), companion.blockPosition(), EXIT_RADIUS);
        if (exit != null) {
            InputDriver.stepToward(companion, Vec3.atBottomCenterOf(exit), false);
        } else if (!noExitNoted) {
            noExitNoted = true;
            com.dwinovo.numen.event.NumenEvents.body(companion,
                    "fell into lava and found no dry foothold within " + EXIT_RADIUS
                            + " blocks; swimming upward while the task is preempted");
        }
        InputDriver.jump(companion);
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        if (!companion.isInLava() || !companion.fcEnabled()) {
            reset();
        }
    }

    @Override
    public String name() {
        return "lava_escape";
    }

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "掉进岩浆时会中断当前动作,持续上浮并朝最近的干燥落脚点撤离";
    }

    private void reset() {
        episodeActive = false;
        noExitNoted = false;
    }

    /** Nearest dry, two-block-high cell with a safe floor. */
    static BlockPos nearestDryFoothold(BlockGetter level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!dryAndStandable(level, candidate)) continue;
                    double distance = dx * dx + dz * dz + dy * dy * 2.0;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean dryAndStandable(BlockGetter level, BlockPos feet) {
        FluidState feetFluid = level.getFluidState(feet);
        FluidState headFluid = level.getFluidState(feet.above());
        if (!feetFluid.isEmpty() || !headFluid.isEmpty()) return false;
        return BlockHelper.canWalkThrough(level, feet)
                && BlockHelper.canWalkThrough(level, feet.above())
                && BlockHelper.canWalkOn(level, feet.below())
                && !BlockHelper.avoidWalkingInto(level, feet)
                && !BlockHelper.avoidWalkingInto(level, feet.above());
    }
}
