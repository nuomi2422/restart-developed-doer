package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Body 语义不得把掉落物 ID 误当成可挖方块 ID。 */
class RddBodyToolsTest {

    @Test void mapsVillageCarrotDropToItsCropBlock() {
        assertEquals("carrots", RddBodyTools.cropBlockPath("carrot"));
        assertEquals("potatoes", RddBodyTools.cropBlockPath("potato"));
        assertEquals("beetroots", RddBodyTools.cropBlockPath("beetroot"));
    }

    @Test void refusesToInventCropBlockForUnrelatedItems() {
        assertNull(RddBodyTools.cropBlockPath("iron_pickaxe"));
        assertNull(RddBodyTools.cropBlockPath("diamond"));
    }
}
