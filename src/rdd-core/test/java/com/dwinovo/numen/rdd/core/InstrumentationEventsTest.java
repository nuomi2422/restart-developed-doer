package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 埋点统一事件通道的硬验收（工单验收 #2 的纯 JVM 半边）：
 * <ul>
 *   <li>每行一个事件、带 schema_version + 游戏 tick；</li>
 *   <li>事件文件整体可 parse、零坏行——坏行必须被 parse 显式报错（绝不静默吞）。</li>
 * </ul>
 */
class InstrumentationEventsTest {

    @Test void lineRoundTripsThroughParse() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companionId", "aaa-bbb");
        data.put("task", "primary-abc-s0");
        data.put("reason", "starving");
        data.put("context", Map.of("inventory", Map.of("minecraft:bread", 3)));

        String line = InstrumentationEvents.line("evt-1", InstrumentationEvents.STARVATION_DEATH, 12345L, data);
        var parsed = InstrumentationEvents.parse(line);

        assertEquals(1, parsed.schemaVersion());
        assertEquals("evt-1", parsed.eventId());
        assertEquals(InstrumentationEvents.STARVATION_DEATH, parsed.type());
        assertEquals(12345L, parsed.gameTime());
        assertEquals("aaa-bbb", parsed.data().get("companionId"));
        assertEquals("starving", parsed.data().get("reason"));
        assertTrue(parsed.data().get("context") instanceof Map<?, ?>);
    }

    @Test void dataDefaultsToEmptyMap() {
        var parsed = InstrumentationEvents.parse(InstrumentationEvents.line("e", InstrumentationEvents.ASSET_MISMATCH, -1L, null));
        assertEquals(-1L, parsed.gameTime());
        assertTrue(parsed.data().isEmpty());
    }

    @Test void rejectsBlankLine() {
        assertThrows(IllegalArgumentException.class, () -> InstrumentationEvents.parse("  "));
    }

    @Test void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> InstrumentationEvents.parse("{not json"));
    }

    @Test void rejectsMissingRequiredEnvelopeFields() {
        assertThrows(IllegalArgumentException.class,
                () -> InstrumentationEvents.parse("{\"schema_version\":1,\"event_id\":\"e\"}"));
    }

    @Test void rejectsUnknownSchemaVersion() {
        String line = "{\"schema_version\":999,\"event_id\":\"e\",\"type\":\"death\",\"data\":{}}";
        assertThrows(IllegalArgumentException.class, () -> InstrumentationEvents.parse(line));
    }

    @Test void rejectsNullType() {
        String line = "{\"schema_version\":1,\"event_id\":\"e\",\"type\":null,\"data\":{}}";
        assertThrows(IllegalArgumentException.class, () -> InstrumentationEvents.parse(line));
    }

    @Test void eventTypesConstantsMatchWorkOrder() {
        // 工单六事件（对应"六个会不会"）
        assertNotNull(InstrumentationEvents.STARVATION_DEATH);
        assertNotNull(InstrumentationEvents.LOOP_DETECTED);
        assertNotNull(InstrumentationEvents.REPEAT_GATHER);
        assertNotNull(InstrumentationEvents.RESOURCE_WASTE);
        assertNotNull(InstrumentationEvents.ASSET_MISMATCH);
        assertNotNull(InstrumentationEvents.RECOVERY_FAILED);
        // 修埋点本身也记事件
        assertNotNull(InstrumentationEvents.INSTRUMENTATION_CHANGE);
    }
}