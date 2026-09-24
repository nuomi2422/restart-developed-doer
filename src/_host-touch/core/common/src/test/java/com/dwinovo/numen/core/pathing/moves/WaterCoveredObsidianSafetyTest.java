package com.dwinovo.numen.core.pathing.moves;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mc")
class WaterCoveredObsidianSafetyTest {

    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void waterAboveWithSolidFloorIsTheOnlyAllowedLiquidVeto() {
        FakeView view = base();
        view.set(TARGET.above(), Blocks.WATER.defaultBlockState());

        assertTrue(MovementHelper.onlyWaterPreventsBreaking(
                view, null, TARGET.getX(), TARGET.getY(), TARGET.getZ(),
                view.getBlockState(TARGET)));
    }

    @Test
    void lavaBelowRejectsEvenWhenWaterCoversTheTarget() {
        FakeView view = base();
        view.set(TARGET.above(), Blocks.WATER.defaultBlockState());
        view.set(TARGET.below(), Blocks.LAVA.defaultBlockState());

        assertFalse(MovementHelper.onlyWaterPreventsBreaking(
                view, null, TARGET.getX(), TARGET.getY(), TARGET.getZ(),
                view.getBlockState(TARGET)));
    }

    @Test
    void horizontalLavaIsNeverDisguisedAsTheWaterException() {
        FakeView view = base();
        view.set(TARGET.above(), Blocks.WATER.defaultBlockState());
        view.set(TARGET.east(), Blocks.LAVA.defaultBlockState());

        assertFalse(MovementHelper.onlyWaterPreventsBreaking(
                view, null, TARGET.getX(), TARGET.getY(), TARGET.getZ(),
                view.getBlockState(TARGET)));
    }

    @Test
    void unstableHorizontalGravelKeepsItsVeto() {
        FakeView view = base();
        view.set(TARGET.above(), Blocks.WATER.defaultBlockState());
        view.set(TARGET.east(), Blocks.GRAVEL.defaultBlockState());

        assertFalse(MovementHelper.onlyWaterPreventsBreaking(
                view, null, TARGET.getX(), TARGET.getY(), TARGET.getZ(),
                view.getBlockState(TARGET)));
    }

    private static FakeView base() {
        FakeView view = new FakeView();
        view.set(TARGET, Blocks.OBSIDIAN.defaultBlockState());
        view.set(TARGET.below(), Blocks.STONE.defaultBlockState());
        return view;
    }

    private static final class FakeView implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
