package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ObservationKeyTest {

    @Test
    void canonicalRoundTrips() {
        ObservationKey key = ObservationKey.of("live", "minecraft:overworld", Topic.ENTITIES,
                "box=0,64,0..3,64,3", "type=minecraft:sheep");
        ObservationKey parsed = ObservationKey.parse(key.canonical());
        assertEquals(key, parsed);
        assertEquals(key.canonical(), parsed.canonical());
    }

    @Test
    void differentNamespacesDoNotCollide() {
        ObservationKey base = ObservationKey.of("live", "minecraft:overworld", Topic.ENTITIES,
                "box=0,64,0..3,64,3", "type=minecraft:sheep");
        assertNotEquals(base.canonical(), ObservationKey.of("other-save", "minecraft:overworld",
                Topic.ENTITIES, "box=0,64,0..3,64,3", "type=minecraft:sheep").canonical());
        assertNotEquals(base.canonical(), ObservationKey.of("live", "minecraft:nether",
                Topic.ENTITIES, "box=0,64,0..3,64,3", "type=minecraft:sheep").canonical());
        assertNotEquals(base.canonical(), ObservationKey.of("live", "minecraft:overworld",
                Topic.ENTITIES, "box=0,64,0..3,64,3", "type=minecraft:cow").canonical());
        assertNotEquals(base.canonical(), ObservationKey.of("live", "minecraft:overworld",
                Topic.ENTITIES, "box=10,64,10..13,64,13", "type=minecraft:sheep").canonical());
    }

    @Test
    void nullsNormalizeToEmptySegments() {
        ObservationKey key = ObservationKey.of(null, null, Topic.ENVIRONMENT, null, null);
        assertEquals("||environment||", key.canonical());
        assertEquals(key, ObservationKey.parse(key.canonical()));
    }

    @Test
    void parseRejectsGarbage() {
        assertNull(ObservationKey.parse("not-a-key"));
        assertNull(ObservationKey.parse("a|b|nope|c|d"));
        assertNull(ObservationKey.parse(null));
    }

    @Test
    void boundsCanonicalRoundTrips() {
        Bounds box = Bounds.of(3, 64, 7, 0, 70, 1);
        Bounds parsed = Bounds.parse(box.canonical());
        assertEquals(box, parsed);
        assertEquals("0,64,1..3,70,7", parsed.canonical());
        assertNull(Bounds.parse("bogus"));
    }
}
