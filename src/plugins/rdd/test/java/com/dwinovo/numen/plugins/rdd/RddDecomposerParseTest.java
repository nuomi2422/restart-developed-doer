package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.BodyInstruction;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RddDecomposer.parse 纯容错解析的单测（不触 LLM/网络/MC）。 */
class RddDecomposerParseTest {

    @Test void parsesValidSubtasksWithBody() {
        String json = """
                {"subtasks":[
                  {"description":"收集 5 个橡木原木","condition":{"asset_key":"minecraft:oak_log","minimum":5},
                   "body":{"task_type":"collect_items","args":{"item":"minecraft:oak_log","count":5}}},
                  {"description":"收 3 块石头","condition":{"asset_key":"minecraft:stone","minimum":3}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(2, specs.size());
        assertEquals("收集 5 个橡木原木", specs.get(0).description());
        assertEquals(5, specs.get(0).condition().get("minimum"));
        assertEquals("minecraft:oak_log", specs.get(0).condition().get("asset_key"));
        BodyInstruction body = specs.get(0).body();
        assertNotNull(body);
        assertEquals("collect_items", body.taskType());
        assertEquals(5, body.args().get("count"));
        assertNull(specs.get(1).body());
        assertEquals(3, specs.get(1).condition().get("minimum"));
    }

    @Test void malformedOrEmptyYieldsEmpty() {
        assertTrue(RddDecomposer.parse("not json {{{").isEmpty());
        assertTrue(RddDecomposer.parse("").isEmpty());
        assertTrue(RddDecomposer.parse("  ").isEmpty());
        assertTrue(RddDecomposer.parse(null).isEmpty());
        assertTrue(RddDecomposer.parse("{}").isEmpty());
        assertTrue(RddDecomposer.parse("{\"subtasks\":[]}").isEmpty());
        assertTrue(RddDecomposer.parse("{\"other\":1}").isEmpty());
    }

    @Test void dropsUnusableSubtasksKeepsGoodOnes() {
        String json = """
                {"subtasks":[
                  {"description":"ok","condition":{"asset_key":"minecraft:oak_log","minimum":1}},
                  {"description":"bad no condition"},
                  {"description":"bad blank key","condition":{"asset_key":"  ","minimum":1}},
                  {"description":"bad negative","condition":{"asset_key":"a","minimum":-2}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(1, specs.size());
        assertEquals("ok", specs.get(0).description());
    }

    @Test void capsAtMaxSubtasks() {
        StringBuilder sb = new StringBuilder("{\"subtasks\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"description\":\"s").append(i).append('"')
                    .append(",\"condition\":{\"asset_key\":\"minecraft:oak_log\",\"minimum\":1}}");
        }
        sb.append("]}");
        List<SubtaskSpec> specs = RddDecomposer.parse(sb.toString());
        assertEquals(RddChainFactory.MAX_SUBTASKS, specs.size());
        // 最早的有效条目保留
        assertEquals("s0", specs.get(0).description());
    }
}
