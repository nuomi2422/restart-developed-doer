package com.dwinovo.numen.api.carrier;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立闹钟（E2）的离线回归：覆盖计划必验清单 ——
 * 低饥饿有/无食物、低血但已饱（不是吃饭指令）、安全夜晚有床/无床/敌怪阻挡、
 * 非主世界不错误睡觉、苦力怕接近/点燃/脱离、多条件同时成立、
 * 未知事实不猜（UNKNOWN ≠ 0）、<b>低血不压掉其它告警</b>（E2 核心）。
 */
class CarrierAlarmsTest {

    private static CarrierChain.Facts facts(String... pairs) {
        Map<String, String> kv = new HashMap<>();
        for (String p : pairs) {
            int i = p.indexOf('=');
            kv.put(p.substring(0, i), p.substring(i + 1));
        }
        return CarrierChain.factsOf(kv, String.join(", ", pairs).toLowerCase());
    }

    private static CarrierAlarms.Hit hit(List<CarrierAlarms.Hit> hits, String rule) {
        return hits.stream().filter(h -> h.rule().equals(rule)).findFirst().orElse(null);
    }

    @Test
    void hungrySaysWhetherFoodExists() {
        var withFood = CarrierAlarms.evaluate(facts("food=5", "food_items=3"));
        assertNotNull(hit(withFood, "hungry"));
        assertTrue(hit(withFood, "hungry").advice().contains("3 个食物"));

        var noFood = CarrierAlarms.evaluate(facts("food=2", "food_items=0"));
        assertNotNull(hit(noFood, "hungry"));
        assertTrue(hit(noFood, "hungry").advice().contains("没有食物"));

        var unknown = CarrierAlarms.evaluate(facts("food=3", "food_items=-1"));
        assertNotNull(hit(unknown, "hungry"));
        assertTrue(hit(unknown, "hungry").advice().contains("未知"));
    }

    @Test
    void fedCompanionHasNoHungryAlarm() {
        var hits = CarrierAlarms.evaluate(facts("food=17", "food_items=4"));
        assertNull(hit(hits, "hungry"));
    }

    @Test
    void notFullSatietyGivesSoftTopUpHint() {
        // 不满（>6 且 <=17）→ 软提示 P2；措辞是「垫一下」，不是命令
        var hits = CarrierAlarms.evaluate(facts("food=17", "food_items=4"));
        assertNotNull(hit(hits, "hungry_soft"));
        assertEquals("P2", hit(hits, "hungry_soft").prio());
        assertTrue(hit(hits, "hungry_soft").advice().contains("不满"));
        // 到饥饿线后由 hungry(P1) 接手，软提示让位（不重复报）
        assertNull(hit(CarrierAlarms.evaluate(facts("food=6", "food_items=2")), "hungry_soft"));
        // 满饱食度不提示
        assertNull(hit(CarrierAlarms.evaluate(facts("food=20", "food_items=5")), "hungry_soft"));
    }

    @Test
    void missingFactsAreUnknownNotZero() {
        // 快照缺 food 键 → 不触发（不能拿 0 猜「饿死了」）
        assertNull(hit(CarrierAlarms.evaluate(facts("hp=20/20")), "hungry"));
        // food_items 未知但 food 已知低 → 触发，措辞按「未知」说
        assertNotNull(hit(CarrierAlarms.evaluate(facts("food=4", "food_items=-1")), "hungry"));
    }

    @Test
    void lowHpIsStateNotEatCommand() {
        // 低血但已饱：只有 low_hp，没有 hungry；措辞是状态+回血建议，不是「去吃饭」命令
        var hits = CarrierAlarms.evaluate(facts("hp=6/20", "food=20", "food_items=5"));
        assertNotNull(hit(hits, "low_hp"));
        assertNull(hit(hits, "hungry"), "已饱不该被低血带出吃饭告警");
        assertTrue(hit(hits, "low_hp").advice().contains("血量偏低"));
    }

    @Test
    void criticalHpEscalatesToP0() {
        assertEquals("P0", hit(CarrierAlarms.evaluate(facts("hp=3/20")), "low_hp").prio());
        assertEquals("P1", hit(CarrierAlarms.evaluate(facts("hp=9/20")), "low_hp").prio());
    }

    @Test
    void lowHpDoesNotSuppressOtherAlarms() {
        // ★ E2 核心：低血条件不得压掉饥饿、夜间、苦力怕告警（旧串行链的失效点）
        var hits = CarrierAlarms.evaluate(facts(
                "hp=3/20", "food=2", "food_items=0", "night=1", "bed=0", "creeper=3", "ignited=true"));
        assertNotNull(hit(hits, "hungry"), "低血不得压掉饥饿告警");
        assertNotNull(hit(hits, "night"), "低血不得压掉夜间告警");
        assertNotNull(hit(hits, "creeper"), "低血不得压掉苦力怕告警");
        assertNotNull(hit(hits, "low_hp"));
        assertEquals(4, hits.size());
    }

    @Test
    void nightVariants() {
        var withBed = CarrierAlarms.evaluate(facts("night=1", "time=14000", "bed=1"));
        assertNotNull(hit(withBed, "night"));
        assertTrue(hit(withBed, "night").advice().contains("睡觉"));

        var noBed = CarrierAlarms.evaluate(facts("night=1", "bed=0"));
        assertNotNull(hit(noBed, "night"));
        assertTrue(hit(noBed, "night").advice().contains("撑过夜晚"));

        var hostiles = CarrierAlarms.evaluate(facts("night=1", "bed=1", "hostile=true"));
        assertNotNull(hit(hostiles, "night"));
        assertTrue(hit(hostiles, "night").advice().contains("威胁"));

        // 非主世界：采样端不写 night 键 → 不触发（不错误建议睡觉）
        assertNull(hit(CarrierAlarms.evaluate(facts("dim=the_nether", "hostile=true")), "night"));
    }

    @Test
    void creeperStagesAndRecovery() {
        assertEquals("P1", hit(CarrierAlarms.evaluate(facts("creeper=12", "ignited=false")), "creeper").prio());
        assertEquals("P0", hit(CarrierAlarms.evaluate(facts("creeper=3", "ignited=false")), "creeper").prio());
        var lit = CarrierAlarms.evaluate(facts("creeper=9", "ignited=true"));
        assertEquals("P0", hit(lit, "creeper").prio());
        assertTrue(hit(lit, "creeper").advice().contains("点燃"));
        // 脱离：采样不到苦力怕 → 键缺失 → 不触发（恢复解除）
        assertNull(hit(CarrierAlarms.evaluate(facts("hp=20/20")), "creeper"));
    }

    @Test
    void allMissingFactsProduceNoAlarms() {
        assertTrue(CarrierAlarms.evaluate(facts("dim=overworld")).isEmpty(),
                "缺事实不许猜：应 0 命中");
    }

    @Test
    void wakeOnlyForCriticalCreeperOrLowHpEdges() {
        // E2.2：主动唤醒只在「会立刻出事」的 P0 边沿
        assertTrue(CarrierAlarms.wakeWorthy("added", hitOf("creeper", "P0")));
        assertTrue(CarrierAlarms.wakeWorthy("escalated", hitOf("creeper", "P0")));
        assertTrue(CarrierAlarms.wakeWorthy("added", hitOf("low_hp", "P0")));
        assertFalse(CarrierAlarms.wakeWorthy("active", hitOf("creeper", "P0")), "平稳期不重复叫");
        assertFalse(CarrierAlarms.wakeWorthy("removed", hitOf("creeper", "P0")), "解除不叫");
        assertFalse(CarrierAlarms.wakeWorthy("added", hitOf("creeper", "P1")), "还远不叫");
        assertFalse(CarrierAlarms.wakeWorthy("added", hitOf("night", "P1")), "夜间是信息不是险情");
        assertFalse(CarrierAlarms.wakeWorthy("added", hitOf("hungry", "P1")), "饥饿已有自己的 urgent 通道");
        assertFalse(CarrierAlarms.wakeWorthy("added", null));
    }

    private static CarrierAlarms.Hit hitOf(String rule, String prio) {
        return new CarrierAlarms.Hit(rule, 1, prio, "facts", "advice");
    }
}
