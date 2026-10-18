package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RddDecomposer.parse 纯容错解析的单测（不触 LLM/网络/MC）。 */
class RddDecomposerParseTest {
    @Test void parsesGroupWithoutInventingSingleItemRequirement() {
        var specs = RddDecomposer.parse("{\"subtasks\":[{\"description\":\"food supply\",\"condition\":{\"group\":\"food\",\"minimum\":16}}]}");
        assertEquals(1, specs.size());
        assertEquals("food", specs.getFirst().condition().get("group"));
        assertFalse(specs.getFirst().condition().containsKey("asset_key"));
        assertDoesNotThrow(() -> com.dwinovo.numen.rdd.core.RddChainFactory.fromSpec(java.util.UUID.randomUUID(), "food", specs));
    }
    @Test void parsesWorldEvidenceAndRejectsFractionalKillThreshold() {
        assertEquals(1, RddDecomposer.parse("{\"subtasks\":[{\"description\":\"base\",\"condition\":{\"type\":\"base\"}}]}").size());
        assertTrue(RddDecomposer.parse("{\"subtasks\":[{\"description\":\"dragon\",\"condition\":{\"type\":\"entity_killed\",\"entity\":\"minecraft:ender_dragon\",\"minimum\":1.5}}]}").isEmpty());
    }

    @Test void parsesValidSubtasks_body被忽略() {
        // 2026-10-07 用户裁决（单驾驶员）：规划器不给身体动作，body 一律忽略。
        // 就算模型/旧链塞了 body，parse 出来也必须是 null。
        String json = """
                {"subtasks":[
                  {"description":"�ռ� 5 ����ľԭľ","condition":{"asset_key":"minecraft:oak_log","minimum":5},
                   "body":{"task_type":"collect_items","args":{"item":"minecraft:oak_log","count":5}}},
                  {"description":"�� 3 ��ʯͷ","condition":{"asset_key":"minecraft:stone","minimum":3}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(2, specs.size());
        assertEquals(5, specs.get(0).condition().get("minimum"));
        assertEquals("minecraft:oak_log", specs.get(0).condition().get("asset_key"));
        assertNull(specs.get(0).body(), "body 必须被忽略（单驾驶员）");
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

    @Test void dropsNonNamespacedAssetKeys() {
        // "合法 JSON 但结构不可执行"必须被识别为失败：裸键/占位goal/大写 永不匹配背包键 -> 丢弃
        String json = """
                {"subtasks":[
                  {"description":"ok","condition":{"asset_key":"minecraft:oak_log","minimum":1}},
                  {"description":"bare key","condition":{"asset_key":"iron_ingot","minimum":1}},
                  {"description":"placeholder","condition":{"asset_key":"goal","minimum":1}},
                  {"description":"case wrong","condition":{"asset_key":"minecraft:Diamond","minimum":1}}
                ]}""";
        List<SubtaskSpec> specs = RddDecomposer.parse(json);
        assertEquals(1, specs.size());
        assertEquals("ok", specs.get(0).description());
        assertEquals("minecraft:oak_log", specs.get(0).condition().get("asset_key"));
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
        // ★ 8 是字面量（契约：二级上限 8 条），刻意不用 RddChainFactory.MAX_SUBTASKS。
        //   变异测试实测：写成常量时把上限改成 20 本测试仍绿——上面循环本来灌 20 条，
        //   封顶到 20 照样成立。详见 RddRedlineContractPinTest。
        assertEquals(8, specs.size());
        // 最早的有效条目保留
        assertEquals("s0", specs.get(0).description());
    }
}
