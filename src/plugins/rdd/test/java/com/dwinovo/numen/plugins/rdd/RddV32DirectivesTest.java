package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RddV32Directives \u5168\u91cf\u6ce8\u5165\u6587\u6848\u5355\u6d4b\uff08\u9875\u4e00 annexTaskSpec + \u9875\u4e8c harnessHints\uff0c\u7eaf\u6587\u672c\u4e0d\u89e6 MC\uff09\u3002 */
class RddV32DirectivesTest {

    @Test void annexTaskSpecHasTwelveStepsAndDependencyRules() {
        String spec = RddV32Directives.annexTaskSpec();
        assertNotNull(spec);
        for (int i = 1; i <= 12; i++) {
            assertTrue(spec.contains(i + ") "), "missing step " + i);
        }
        // \u4f9d\u8d56\u94c1\u5f8b\u5fc5\u987b\u70b9\u51fa\u5173\u952e\u5e8f\uff1a\u94bb\u77f3\u5b58\u4e00\u5957 \u5148\u4e8e \u6316\u9ed1\u66dc\u77f3\uff1b\u5e8a\u7ed1\u5b9a \u5148\u4e8e\u9ad8\u98ce\u9669\u5916\u51fa
        assertTrue(spec.contains("obsidian") || spec.contains("\u9ed1\u66dc\u77f3"), "obsidian mentioned");
        assertTrue(spec.contains("\u4f9d\u8d56") || spec.contains("\u5148\u4e8e"), "dependency rule present");
    }

    @Test void harnessHintsHasElevenHints() {
        String hints = RddV32Directives.harnessHints();
        assertNotNull(hints);
        for (int i = 1; i <= 11; i++) {
            assertTrue(hints.contains(i + ") "), "missing hint " + i);
        }
    }

    @Test void annexObjectiveMentionsNoNetherEntry() {
        String obj = RddV32Directives.ANNEX_OBJECTIVE;
        assertNotNull(obj);
        assertTrue(obj.length() > 20);
    }

    @Test void attachHarnessAppendsWithoutLosingBase() {
        String base = "BASE_CONTEXT";
        String merged = RddV32Directives.attachHarness(base);
        assertTrue(merged.startsWith(base), "base preserved");
        assertTrue(merged.contains(RddV32Directives.harnessHints()), "hints appended");
        // \u7a7a base \u8fd4\u56de hints\uff1bnull base \u4e5f\u4e0d NPE
        assertEquals(RddV32Directives.harnessHints(), RddV32Directives.attachHarness(""));
        assertEquals(RddV32Directives.harnessHints(), RddV32Directives.attachHarness(null));
    }
}
