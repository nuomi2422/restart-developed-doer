package com.dwinovo.numen.plugins.experience;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置只读经验层的解析测试（纯函数，<b>mock</b> 数据，不读真实文件）。
 */
class BuiltinExperienceTest {

    private static final String GOOD = "{\"v\":1,\"id\":\"fail|deep-diamond\",\"type\":\"FAILURE\","
            + "\"title\":\"深埋钻石不能只靠自动挖矿\",\"description\":\"自动挖矿在深板岩层找不到钻石\","
            + "\"root_cause\":\"自动挖矿不会主动下探到 y=-58 的深板岩层\","
            + "\"recommended_response\":\"改为手动挖竖井下探，或用阶梯式下行\","
            + "\"trigger_strings\":[\"钻石\",\"深板岩\"],\"tags\":[\"钻石\",\"挖矿\"],"
            + "\"maturity\":\"VERIFIED\",\"verified_count\":2,\"priority\":80}";

    @Test
    void 解析正常条_出处固定为builtin() {
        List<PlanningKnowledge.Item> items = BuiltinExperience.parse(GOOD);

        assertEquals(1, items.size());
        PlanningKnowledge.Item item = items.get(0);
        assertEquals("fail|deep-diamond", item.id());
        assertEquals(BuiltinExperience.ORIGIN, item.origin(), "内置层出处必须是 builtin，便于与亲历经验区分");
        assertEquals("VERIFIED", item.maturity());
        assertTrue(item.response().contains("手动挖竖井"));
        assertTrue(item.tags().contains("钻石"));
    }

    @Test
    void 坏行跳过_其余条目照常生效() {
        String jsonl = GOOD + "\n"
                + "{这不是合法 JSON\n"
                + "\n"
                + "// 注释行\n"
                + "{\"title\":\"第二条\",\"recommended_response\":\"做法\"}\n";

        List<PlanningKnowledge.Item> items = BuiltinExperience.parse(jsonl);

        assertEquals(2, items.size(), "坏行与注释应被跳过，好行保留");
        assertEquals("fail|deep-diamond", items.get(0).id());
        assertEquals("第二条", items.get(1).title());
    }

    @Test
    void 空壳条_被丢弃() {
        // 三个正文字段全空 → 没有注入价值
        assertTrue(BuiltinExperience.parse("{\"id\":\"x\",\"title\":\"\"}").isEmpty());
    }

    @Test
    void 空输入_不抛异常() {
        assertTrue(BuiltinExperience.parse(null).isEmpty());
        assertTrue(BuiltinExperience.parse("").isEmpty());
        assertTrue(BuiltinExperience.parse("   \n \n").isEmpty());
    }

    @Test
    void 缺字段条_用默认值补全而不是崩溃() {
        List<PlanningKnowledge.Item> items = BuiltinExperience.parse(
                "{\"title\":\"只有标题和做法\",\"recommended_response\":\"照做\"}");

        assertEquals(1, items.size());
        assertEquals("OBSERVED", items.get(0).maturity(), "缺成熟度按 OBSERVED 兜底");
        assertFalse(items.get(0).id().isBlank(), "缺 id 也要有可审计的标识");
    }
}
