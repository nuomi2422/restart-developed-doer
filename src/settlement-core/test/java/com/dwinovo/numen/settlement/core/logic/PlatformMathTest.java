package com.dwinovo.numen.settlement.core.logic;

import com.dwinovo.numen.settlement.core.TestData;
import com.dwinovo.numen.settlement.core.model.BlockBox;
import com.dwinovo.numen.settlement.core.model.CellKey;
import com.dwinovo.numen.settlement.core.model.Edge;
import com.dwinovo.numen.settlement.core.model.Partition;
import com.dwinovo.numen.settlement.core.model.PlatformPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformMathTest {

    private static PlatformPlan square25() {
        return PlatformPlan.square25("base1", TestData.DIM, 100, 64, 200);
    }

    @Test
    void defaultPlatformIs25x25With25Cells() {
        PlatformPlan p = square25();
        assertEquals(5, p.cellsX());
        assertEquals(5, p.cellsZ());
        assertEquals(25, p.sizeX());
        assertEquals(25, p.sizeZ());
        assertEquals(124, p.maxX());
        assertEquals(224, p.maxZ());
    }

    @Test
    void cellBoundsAreInclusiveAndFiveWide() {
        PlatformPlan p = square25();
        BlockBox c00 = PlatformMath.cellBounds(p, CellKey.of(0, 0));
        assertEquals(100, c00.minX());
        assertEquals(104, c00.maxX());
        assertEquals(200, c00.minZ());
        assertEquals(204, c00.maxZ());
        assertEquals(64, c00.minY());
        assertEquals(68, c00.maxY());

        BlockBox c10 = PlatformMath.cellBounds(p, CellKey.of(1, 0));
        assertEquals(105, c10.minX());
        assertEquals(109, c10.maxX());
    }

    @Test
    void cellAtMapsWorldColumnsBackToCells() {
        PlatformPlan p = square25();
        assertEquals(Optional.of(CellKey.of(0, 0)), PlatformMath.cellAt(p, 100, 200));
        assertEquals(Optional.of(CellKey.of(1, 0)), PlatformMath.cellAt(p, 105, 200));
        assertEquals(Optional.of(CellKey.of(4, 4)), PlatformMath.cellAt(p, 124, 224));
        assertTrue(PlatformMath.cellAt(p, 125, 200).isEmpty());
        assertTrue(PlatformMath.cellAt(p, 99, 200).isEmpty());
    }

    @Test
    void neighborKnowsPlatformEdges() {
        PlatformPlan p = square25();
        assertEquals(Optional.of(CellKey.of(1, 0)), PlatformMath.neighbor(p, CellKey.of(0, 0), Edge.EAST));
        assertEquals(Optional.of(CellKey.of(0, 1)), PlatformMath.neighbor(p, CellKey.of(0, 0), Edge.SOUTH));
        assertTrue(PlatformMath.neighbor(p, CellKey.of(0, 0), Edge.WEST).isEmpty());
        assertTrue(PlatformMath.neighbor(p, CellKey.of(4, 4), Edge.EAST).isEmpty());
    }

    @Test
    void sharedBoundaryCoversTheTwoFacingColumnsWhenGapIsZero() {
        PlatformPlan p = square25();
        BlockBox seam = PlatformMath.sharedBoundary(p, CellKey.of(0, 0), CellKey.of(1, 0)).orElseThrow();
        // low cell last column (104) through high cell first column (105)
        assertEquals(104, seam.minX());
        assertEquals(105, seam.maxX());
        assertEquals(65, seam.minY());
        assertEquals(68, seam.maxY());
        assertEquals(200, seam.minZ());
        assertEquals(204, seam.maxZ());
    }

    @Test
    void sharedBoundaryIsDirectionSymmetric() {
        PlatformPlan p = square25();
        BlockBox a = PlatformMath.sharedBoundary(p, CellKey.of(0, 0), CellKey.of(1, 0)).orElseThrow();
        BlockBox b = PlatformMath.sharedBoundary(p, CellKey.of(1, 0), CellKey.of(0, 0)).orElseThrow();
        assertEquals(a, b);
    }

    @Test
    void deWallClearsOnlyTheSeamAboveTheFloor() {
        PlatformPlan p = square25();
        List<BlockEdit> edits = PlatformMath.deWall(p, CellKey.of(0, 0), CellKey.of(1, 0));
        // 2 columns * 5 z * 4 high = 40
        assertEquals(40, edits.size());
        assertTrue(edits.stream().allMatch(BlockEdit::clear));
        assertTrue(edits.stream().allMatch(e -> e.y() >= 65 && e.y() <= 68));
        assertFalse(edits.stream().anyMatch(e -> e.y() == 64), "floor must survive");
    }

    @Test
    void flattenFillsFloorAndClearsAbove() {
        PlatformPlan p = square25();
        List<BlockEdit> edits = PlatformMath.flatten(p, "minecraft:stone");
        assertEquals(25 * 25 * (1 + 4), edits.size());
        long floors = edits.stream().filter(e -> !e.clear()).count();
        assertEquals(625, floors);
        assertTrue(edits.stream().filter(e -> !e.clear())
                .allMatch(e -> "minecraft:stone".equals(e.blockId()) && e.y() == 64));
    }

    @Test
    void anchorSitsOnTopOfTheFloor() {
        PlatformPlan p = square25();
        assertEquals(100, PlatformMath.anchor(p, CellKey.of(0, 0)).x());
        assertEquals(65, PlatformMath.anchor(p, CellKey.of(0, 0)).y());
        assertEquals(210, PlatformMath.anchor(p, CellKey.of(0, 2)).z());
    }

    @Test
    void gapCreatesAGapColumnBetweenCells() {
        PlatformPlan p = new PlatformPlan("g", TestData.DIM, 0, 64, 0,
                2, 2, 5, 1, 4, List.of(), List.of());
        assertEquals(11, p.sizeX());
        assertEquals(11, p.sizeZ());
        // gap column 5 belongs to no cell
        assertTrue(PlatformMath.cellAt(p, 5, 0).isEmpty());
        assertEquals(Optional.of(CellKey.of(0, 0)), PlatformMath.cellAt(p, 4, 0));
        assertEquals(Optional.of(CellKey.of(1, 0)), PlatformMath.cellAt(p, 6, 0));
    }

    @Test
    void partitionDefaultsToWallAndCanOpen() {
        PlatformPlan p = square25();
        assertEquals(Partition.WALL, p.partitionOf(CellKey.of(0, 0), CellKey.of(1, 0)));
        PlatformPlan opened = p.withPartition(CellKey.of(0, 0), CellKey.of(1, 0), Partition.OPEN);
        assertEquals(Partition.OPEN, opened.partitionOf(CellKey.of(0, 0), CellKey.of(1, 0)));
        // normalization: same edge regardless of order
        assertEquals(Partition.OPEN, opened.partitionOf(CellKey.of(1, 0), CellKey.of(0, 0)));
    }
}
