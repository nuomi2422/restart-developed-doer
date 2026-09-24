package com.dwinovo.numen.core.task.chain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("mc")
class HazardEscapeChainsTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void lavaEscapeChoosesNearestDryTwoHighFoothold() {
        FakeView view = new FakeView();
        BlockPos origin = new BlockPos(0, 64, 0);
        view.set(origin, Blocks.LAVA.defaultBlockState());
        view.set(origin.east().below(), Blocks.STONE.defaultBlockState());

        assertEquals(origin.east(),
                LavaEscapeChain.nearestDryFoothold(view, origin, 3));
    }

    @Test
    void lavaEscapeDoesNotInventAnExitWithoutAFloor() {
        FakeView view = new FakeView();
        BlockPos origin = new BlockPos(0, 64, 0);
        view.set(origin, Blocks.LAVA.defaultBlockState());

        assertNull(LavaEscapeChain.nearestDryFoothold(view, origin, 3));
    }

    @Test
    void suffocationEscapeSelectsTheIntersectingBreakableBlock() {
        FakeView view = new FakeView();
        BlockPos obstruction = new BlockPos(0, 65, 0);
        view.set(obstruction, Blocks.STONE.defaultBlockState());
        AABB body = new AABB(0.2, 64.0, 0.2, 0.8, 65.8, 0.8);

        assertEquals(obstruction,
                SuffocationEscapeChain.escapeBlock(view, body, new Vec3(0.5, 65.62, 0.5)));
    }

    @Test
    void suffocationEscapeRefusesUnbreakableOverlap() {
        FakeView view = new FakeView();
        view.set(new BlockPos(0, 65, 0), Blocks.BEDROCK.defaultBlockState());
        AABB body = new AABB(0.2, 64.0, 0.2, 0.8, 65.8, 0.8);

        assertNull(SuffocationEscapeChain.escapeBlock(
                view, body, new Vec3(0.5, 65.62, 0.5)));
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
