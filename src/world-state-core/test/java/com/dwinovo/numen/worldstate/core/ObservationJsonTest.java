package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationJsonTest {

    @Test
    void roundTripsEnvironmentAndEntities() {
        ObservationKey envKey = ObservationRequest.environment("minecraft:overworld").keyFor("live");
        Observation env = TestSupport.observation(envKey,
                new EnvironmentPayload("minecraft:overworld", 6000L, true, false),
                1_000L, Coverage.COMPLETE, Freshness.CURRENT, 1);

        ObservationKey entKey = TestSupport.entityKey("live", "minecraft:overworld",
                Bounds.of(0, 64, 0, 3, 64, 3), "minecraft:sheep");
        EntityCountPayload entPayload = new EntityCountPayload("minecraft:overworld",
                Bounds.of(0, 64, 0, 3, 64, 3), "minecraft:sheep", null, 2, 16, 0);
        Observation ent = TestSupport.observation(entKey, entPayload, 1_000L, Coverage.COMPLETE,
                Freshness.CURRENT, 2);

        String json = ObservationJson.toJson(List.of(env, ent));
        assertTrue(json.contains("\"version\":1"));

        List<Observation> loaded = ObservationJson.fromJson(json);
        assertEquals(2, loaded.size());

        Observation loadedEnv = byTopic(loaded, Topic.ENVIRONMENT);
        assertEquals(new EnvironmentPayload("minecraft:overworld", 6000L, true, false),
                loadedEnv.payload());
        assertEquals(env.key(), loadedEnv.key());
        // 重启载入的动态观测必须标为历史，不能冒充实时
        assertEquals(Freshness.HISTORICAL, loadedEnv.freshness());

        Observation loadedEnt = byTopic(loaded, Topic.ENTITIES);
        assertEquals(entPayload, loadedEnt.payload());
        assertEquals(2L, loadedEnt.revision());
    }

    @Test
    void unknownTopicIsDroppedForwardCompatible() {
        String json = "{\"version\":1,\"observations\":[{\"worldId\":\"live\","
                + "\"dimension\":\"minecraft:overworld\",\"topic\":\"containers\",\"target\":\"\","
                + "\"filter\":\"\",\"payload\":{},\"source\":\"x\",\"origin\":\"DIRECT\","
                + "\"gameTick\":0,\"epochMs\":0,\"coverage\":\"COMPLETE\",\"revision\":1,"
                + "\"dataVersion\":1}]}";
        assertTrue(ObservationJson.fromJson(json).isEmpty());
    }

    @Test
    void corruptInputIsTolerated() {
        assertTrue(ObservationJson.fromJson("{not json").isEmpty());
        assertTrue(ObservationJson.fromJson("").isEmpty());
        assertTrue(ObservationJson.fromJson(null).isEmpty());
    }

    private static Observation byTopic(List<Observation> list, Topic topic) {
        return list.stream().filter(o -> o.key().topic() == topic).findFirst().orElseThrow();
    }
}
