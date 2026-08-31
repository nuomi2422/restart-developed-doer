package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import com.dwinovo.numen.ac.api.ExecutionContext;
import com.dwinovo.numen.ac.api.ExecutionRecord;
import com.dwinovo.numen.ac.api.StepResult;
import com.dwinovo.numen.ac.api.ToolSchema;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AcSchemaCatalogTest {

    private static final ExecutionContext CTX = Map::of;

    private static ToolSchema schema(String name, ToolSchema.Param... params) {
        return new ToolSchema(name, "1", name, List.of(params), false);
    }

    @Test
    void catalogIsStableSortedAndReadable() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("z_tool", (p, c) -> StepResult.success(Map.of()));
        r.register("a_tool", (p, c) -> StepResult.success(Map.of()));
        assertEquals(List.of("a_tool", "z_tool"), r.toolNames());
        assertEquals(2, r.schemas().size());
        assertEquals("a_tool", r.schemas().get(0).name());
    }

    @Test
    void duplicateRegistrationRejected() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("x", (p, c) -> StepResult.success(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> r.register("x", (p, c) -> StepResult.success(Map.of())));
    }

    @Test
    void schemaRejectsMissingRequiredAndUnknownParams() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("mine", (p, c) -> StepResult.success(Map.of("mined", 1)),
                schema("mine", ToolSchema.Param.req("target", ToolSchema.Type.STRING)));
        var ac = new AcDefinition("bad",
                List.of(new AcDefinition.AcStep("s", "mine", Map.of("target", "iron", "extra", 1))));
        var rec = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.FAILED, rec.status());
        assertTrue(rec.message().contains("未知参数"), rec.message());

        var ac2 = new AcDefinition("missing",
                List.of(new AcDefinition.AcStep("s", "mine", Map.of())));
        var rec2 = new AcExecutor(r).execute(ac2, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.FAILED, rec2.status());
        assertTrue(rec2.message().contains("缺少必填参数"), rec2.message());
    }

    @Test
    void schemaAcceptsMatchingParamsAndRunsTool() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("mine", (p, c) -> StepResult.success(Map.of("mined", p.get("target"))),
                schema("mine", ToolSchema.Param.req("target", ToolSchema.Type.STRING)));
        var ac = new AcDefinition("ok",
                List.of(new AcDefinition.AcStep("s", "mine", Map.of("target", "iron"))));
        var rec = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.SUCCESS, rec.status());
        assertEquals("iron", rec.output().get("mined"));
    }

    @Test
    void typeMismatchRejected() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register("num", (p, c) -> StepResult.success(Map.of()),
                schema("num", ToolSchema.Param.req("count", ToolSchema.Type.INTEGER)));
        var ac = new AcDefinition("ty",
                List.of(new AcDefinition.AcStep("s", "num", Map.of("count", "many"))));
        var rec = new AcExecutor(r).execute(ac, Map.of(), CTX);
        assertEquals(ExecutionRecord.Status.FAILED, rec.status());
        assertTrue(rec.message().contains("整数"), rec.message());
    }

    @Test
    void fingerprintStableAcrossMapOrdering() {
        var a = new AcDefinition("fp", "1", List.of(new AcDefinition.AcStep("s", "t", Map.of("b", 1, "a", 2))));
        var b = new AcDefinition("fp", "1", List.of(new AcDefinition.AcStep("s", "t", Map.of("a", 2, "b", 1))));
        assertEquals(AcFingerprint.of(a), AcFingerprint.of(b));
        // 数字归一：1 vs 1.0
        var c = new AcDefinition("fp", "1", List.of(new AcDefinition.AcStep("s", "t", Map.of("b", 1.0, "a", 2))));
        assertEquals(AcFingerprint.of(a), AcFingerprint.of(c));
        // 内容变化 → fingerprint 变
        var d = new AcDefinition("fp", "1", List.of(new AcDefinition.AcStep("s", "t", Map.of("b", 3, "a", 2))));
        assertNotEquals(AcFingerprint.of(a), AcFingerprint.of(d));
        // 版本变化 → fingerprint 变
        var e = new AcDefinition("fp", "2", List.of(new AcDefinition.AcStep("s", "t", Map.of("b", 1, "a", 2))));
        assertNotEquals(AcFingerprint.of(a), AcFingerprint.of(e));
    }

    @Test
    void strictJsonRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> AcJson.load(new StringReader("not json")));
        assertThrows(IllegalArgumentException.class,
                () -> AcJson.load(new StringReader("{\"steps\":[]}")));
        assertThrows(IllegalArgumentException.class,
                () -> AcJson.load(new StringReader("{\"name\":\"x\",\"version\":\"1\",\"steps\":[{\"tool\":\"t\"}]}")));
        assertThrows(IllegalArgumentException.class,
                () -> AcJson.load(new StringReader("{\"name\":\"x\",\"version\":\"1\",\"steps\":\"not-array\"}")));
        // 嵌套过深参数
        String deep = "{\"name\":\"x\",\"version\":\"1\",\"steps\":[{\"id\":\"s\",\"tool\":\"t\",\"parameters\":"
                + "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":{\"j\":1}}}}}}}}}}}]}";
        assertThrows(IllegalArgumentException.class, () -> AcJson.load(new StringReader(deep)));
    }

    @Test
    void strictJsonLoadsNestedAndTypedParams() {
        var ac = AcJson.load(new StringReader("{\"name\":\"x\",\"version\":\"3\",\"steps\":["
                + "{\"id\":\"s\",\"tool\":\"t\",\"parameters\":{\"n\":2,\"flag\":true,"
                + "\"list\":[1,2],\"obj\":{\"k\":\"v\"}}}]}"));
        assertEquals("x", ac.name());
        assertEquals("3", ac.version());
        var params = ac.steps().get(0).parameters();
        assertEquals(2L, params.get("n"));
        assertEquals(Boolean.TRUE, params.get("flag"));
        assertEquals(List.of(1L, 2L), params.get("list"));
        assertEquals(Map.of("k", "v"), params.get("obj"));
    }
}
