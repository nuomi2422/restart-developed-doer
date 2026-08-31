package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MutationStaticCheckerTest {
    @Test
    void acceptsSafeGeneratedSource() {
        var result = MutationStaticChecker.check(
                "package com.dwinovo.numen.plugins.selfcompile.generated;\npublic final class A {}\n");
        assertTrue(result.accepted());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void rejectsWrongPackageAndDangerousTokens() {
        var result = MutationStaticChecker.check(
                "package other;\nclass A { void x() { System.exit(0); } }\n");
        assertFalse(result.accepted());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("package")));
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("System.exit")));
    }

    @Test
    void rejectsBlankAndOversizedSource() {
        assertFalse(MutationStaticChecker.check(" ").accepted());
        assertFalse(MutationStaticChecker.check(
                "package com.dwinovo.numen.plugins.selfcompile.generated;\n" + "x".repeat(200_001)).accepted());
    }
}
