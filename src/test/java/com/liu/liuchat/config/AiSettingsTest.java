package com.liu.liuchat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsTest {
    @Test void assistantInheritsSharedSettingsWhenBlank() {
        assertEquals("mimo-v2.6-flash", ConfigManager.assistantValue("", "mimo-v2.6-flash"));
        assertEquals("shared-key", ConfigManager.assistantValue(null, "shared-key"));
        assertEquals("assistant-model", ConfigManager.assistantValue("assistant-model", "shared-model"));
    }

    @Test void bundledTokenSavingDefaultsExist() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1024, defaults.getInt("ai.assistant.max-tokens"));
            assertEquals(300, defaults.getInt("ai.assistant.cache-seconds"));
            assertEquals(20, defaults.getInt("ai.assistant.history-messages"));
            assertEquals(4000, defaults.getInt("ai.assistant.history-chars"));
            assertEquals(0, defaults.getInt("ai.assistant.history-seconds"));
            assertTrue(defaults.getBoolean("ai.assistant.history-persist"));
        }
    }
}
