package com.dwinovo.numen.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NumenPromptsTest {
    @Test
    void internalRddContextIsPrivateByDefault() {
        String prompt = NumenPrompts.ENTITY_PROMPT;
        assertTrue(prompt.contains("private control context"));
        assertTrue(prompt.contains("Never repeat it in ordinary companion chat"));
    }
}
