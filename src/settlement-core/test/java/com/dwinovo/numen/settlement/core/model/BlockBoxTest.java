package com.dwinovo.numen.settlement.core;

import com.dwinovo.numen.settlement.core.model.BlockBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBoxTest {

    @Test
    void normalizesReversedCorners() {
        BlockBox box = new BlockBox(10, 70, 30, 5, 64, 20);
        assertEquals(5, box.minX());
        assertEquals(10, box.maxX());
        assertEquals(64, box.minY());
        assertEquals(70, box.maxY());
        assertEquals(20, box.minZ());
        assertEquals(30, box.maxZ());
    }

    @Test
    void containsUsesInclusiveBounds() {
        BlockBox box = BlockBox.of(0, 64, 0, 4, 68, 4);
        assertTrue(box.contains(0, 64, 0));
        assertTrue(box.contains(4, 68, 4));
        assertFalse(box.contains(5, 64, 0));
        assertFalse(box.contains(0, 69, 0));
    }

    @Test
    void volumeAndSizes() {
        BlockBox box = BlockBox.of(0, 0, 0, 4, 4, 4);
        assertEquals(5, box.sizeX());
        assertEquals(5, box.sizeY());
        assertEquals(5, box.sizeZ());
        assertEquals(125L, box.volume());
    }

    @Test
    void intersectsOnlyWhenOverlappingOnEveryAxis() {
        BlockBox a = BlockBox.of(0, 0, 0, 4, 4, 4);
        assertTrue(a.intersects(BlockBox.of(4, 4, 4, 8, 8, 8)));
        assertFalse(a.intersects(BlockBox.of(5, 0, 0, 9, 4, 4)));
    }

    @Test
    void columnCheckIgnoresY() {
        BlockBox box = BlockBox.of(0, 64, 0, 4, 68, 4);
        assertTrue(box.containsColumn(2, 2));
        assertFalse(box.containsColumn(2, 5));
    }
}
