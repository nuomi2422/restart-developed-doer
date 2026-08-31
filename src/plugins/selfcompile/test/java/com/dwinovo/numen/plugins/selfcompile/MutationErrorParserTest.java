package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MutationErrorParserTest {

    @Test
    void parsesEnglishCompileErrors() {
        var errors = MutationErrorParser.parse(
                "Tool.java:12: error: cannot find symbol\n"
                        + "  symbol:   class Diamond\n"
                        + "Tool.java:34:5: error: incompatible types\n");
        assertEquals(2, errors.size());
        assertEquals("Tool.java", errors.get(0).file());
        assertEquals(12, errors.get(0).line());
        assertEquals(0, errors.get(0).column());
        assertEquals("cannot find symbol", errors.get(0).message());
        assertEquals(34, errors.get(1).line());
        assertEquals(5, errors.get(1).column());
        assertEquals("incompatible types", errors.get(1).message());
    }

    @Test
    void parsesChineseCompileErrors() {
        var errors = MutationErrorParser.parse("Foo.java:12: 错误: 找不到符号\n  符号:   类 Diamond");
        assertEquals(1, errors.size());
        assertEquals("Foo.java", errors.get(0).file());
        assertEquals(12, errors.get(0).line());
        assertEquals("找不到符号", errors.get(0).message());
    }

    @Test
    void blankOrNullReturnsEmpty() {
        assertTrue(MutationErrorParser.parse(null).isEmpty());
        assertTrue(MutationErrorParser.parse("").isEmpty());
    }
}
