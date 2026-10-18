package com.dwinovo.numen.settlement.core.model;

import com.dwinovo.numen.settlement.core.TestData;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacilityRegistryTest {

    private static FacilityRecord farm() {
        return TestData.facility("farm1", FacilityKind.FARM, BlockBox.of(0, 64, 0, 4, 68, 4));
    }

    @Test
    void withAndWithoutAreImmutableCopies() {
        FacilityRegistry empty = new FacilityRegistry();
        FacilityRegistry one = empty.with(farm());
        assertEquals(0, empty.size());
        assertEquals(1, one.size());
        assertEquals(0, one.without("farm1").size());
    }

    @Test
    void withOverwritesSameId() {
        FacilityRecord updated = farm().withConstruction(
                new ConstructionProgress(10, 0, 0, 10, 42L));
        FacilityRegistry r = new FacilityRegistry().with(farm()).with(updated);
        assertEquals(1, r.size());
        assertEquals(10, r.byId("farm1").orElseThrow().construction().total());
    }

    @Test
    void filtersByDimension() {
        FacilityRegistry r = new FacilityRegistry().with(farm());
        assertEquals(1, r.inDimension(TestData.DIM).size());
        assertEquals(0, r.inDimension("minecraft:the_nether").size());
    }

    @Test
    void coveringColumnFindsTheOwner() {
        FacilityRegistry r = new FacilityRegistry().with(farm());
        assertEquals(1, r.coveringColumn(TestData.DIM, 2, 2).size());
        assertEquals(0, r.coveringColumn(TestData.DIM, 9, 9).size());
    }

    @Test
    void byIdMissingIsEmpty() {
        assertTrue(new FacilityRegistry().byId("nope").isEmpty());
        assertEquals(Optional.of("farm1"), new FacilityRegistry().with(farm()).byId("farm1")
                .map(FacilityRecord::id));
    }

    @Test
    void rejectsBadIds() {
        assertThrows();
    }

    private static void assertThrows() {
        boolean threw = false;
        try {
            new FacilityRecord("Bad Id!", "o", "b", TestData.DIM, FacilityKind.FARM,
                    BlockBox.of(0, 0, 0, 1, 1, 1), 0, null, null, null,
                    java.util.List.of(), java.util.List.of(), null, null,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.Map.of(),
                    null, null, null, 0L, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "an id with spaces must be rejected");
    }

    @Test
    void emptyRegistryIsEmpty() {
        assertTrue(new FacilityRegistry().isEmpty());
        assertFalse(new FacilityRegistry().with(farm()).isEmpty());
    }
}
