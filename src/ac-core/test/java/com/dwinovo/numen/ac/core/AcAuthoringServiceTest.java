package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.StepResult;
import com.dwinovo.numen.ac.api.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AcAuthoringServiceTest {

    private DefaultToolRegistry registry() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("ok", (p, c) -> StepResult.success(Map.of()));
        r.register("mine", (p, c) -> StepResult.success(Map.of()),
                new ToolSchema("mine", "1", "mine", List.of(
                        ToolSchema.Param.req("target", ToolSchema.Type.STRING)), false));
        return r;
    }

    private AcAuthoringService service() {
        return new AcAuthoringService(registry(), new InMemoryAcVersionStore());
    }

    private AcDefinition ac(String name, String version, AcDefinition.AcStep... steps) {
        return new AcDefinition(name, version, List.of(steps));
    }

    @Test
    void validJsonPublishesAndLoads() {
        AcAuthoringService svc = service();
        String json = "{\"name\":\"wood\",\"version\":\"1\",\"steps\":[{\"id\":\"s\",\"tool\":\"ok\",\"parameters\":{}}]}";
        assertTrue(svc.validateJson(json).valid());
        AcDefinition published = svc.publishJson(json);
        assertEquals("wood", published.name());
        assertEquals(List.of("1"), svc.listVersions("wood"));
        assertTrue(svc.load("wood", "1").isPresent());
        assertTrue(svc.loadLatest("wood").isPresent());
    }

    @Test
    void unknownToolRejectedAtAuthoringTime() {
        AcAuthoringService svc = service();
        String json = "{\"name\":\"bad\",\"version\":\"1\",\"steps\":[{\"id\":\"s\",\"tool\":\"not_registered\",\"parameters\":{}}]}";
        var v = svc.validateJson(json);
        assertFalse(v.valid());
        assertTrue(v.reason().contains("not_registered"), v.reason());
        assertThrows(IllegalArgumentException.class, () -> svc.publishJson(json));
    }

    @Test
    void illegalReplaceKeepsCurrentVersion() {
        AcAuthoringService svc = service();
        String good = "{\"name\":\"mine_ac\",\"version\":\"1\",\"steps\":[{\"id\":\"s\",\"tool\":\"mine\",\"parameters\":{\"target\":\"iron\"}}]}";
        AcDefinition v1 = svc.publishJson(good);

        // 非法替换：参数不满足 schema（缺 required target）
        String bad = "{\"name\":\"mine_ac\",\"version\":\"2\",\"steps\":[{\"id\":\"s\",\"tool\":\"mine\",\"parameters\":{}}]}";
        assertFalse(svc.validateJson(bad).valid());
        assertThrows(IllegalArgumentException.class, () -> svc.publishJson(bad));

        // 旧版本仍可用，新版本未写入
        assertTrue(svc.load("mine_ac", "1").isPresent());
        assertFalse(svc.load("mine_ac", "2").isPresent());
        assertEquals(v1, svc.loadLatest("mine_ac").orElseThrow());
    }

    @Test
    void replaceAddsNewVersionAtomically() {
        AcAuthoringService svc = service();
        svc.publishJson("{\"name\":\"a\",\"version\":\"1\",\"steps\":[{\"id\":\"s\",\"tool\":\"ok\",\"parameters\":{}}]}");
        svc.publishJson("{\"name\":\"a\",\"version\":\"2\",\"steps\":[{\"id\":\"s\",\"tool\":\"ok\",\"parameters\":{}}]}");
        assertEquals(List.of("1", "2"), svc.listVersions("a"));
        assertEquals("2", svc.loadLatest("a").orElseThrow().version());
        // v1 保留
        assertEquals("1", svc.load("a", "1").orElseThrow().version());
    }

    @Test
    void stepCountLimitRejected() {
        AcAuthoringService svc = service();
        var steps = new java.util.ArrayList<AcDefinition.AcStep>();
        for (int i = 0; i < AcAuthoringService.MAX_STEPS + 1; i++) {
            steps.add(new AcDefinition.AcStep("s" + i, "ok", Map.of()));
        }
        var v = svc.validate(ac("too_many", "1", steps.toArray(new AcDefinition.AcStep[0])));
        assertFalse(v.valid());
        assertTrue(v.reason().contains("上限"), v.reason());
    }
}
