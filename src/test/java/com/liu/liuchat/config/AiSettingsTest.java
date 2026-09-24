package com.liu.liuchat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiSettingsTest {
    @Test void assistantInheritsSharedSettingsWhenBlank() {
        assertEquals("mimo-v2.6-flash", ConfigManager.assistantValue("", "mimo-v2.6-flash"));
        assertEquals("shared-key", ConfigManager.assistantValue(null, "shared-key"));
        assertEquals("assistant-model", ConfigManager.assistantValue("assistant-model", "shared-model"));
    }
}
