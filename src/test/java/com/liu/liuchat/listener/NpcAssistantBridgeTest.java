package com.liu.liuchat.listener;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcAssistantBridgeTest {
    @Test void bindsOnlyConfiguredEnabledNpcIds() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                npcs:
                  '12':
                    assistant: bot
                    title: Guide
                  '13':
                    enable: false
                    assistant: bot
                  invalid:
                    assistant: bot
                  '14':
                    assistant: '../private'
                """);
        var bindings = NpcAssistantBridge.parseBindings(config);
        assertEquals(1, bindings.size());
        assertTrue(bindings.containsKey(12));
        assertFalse(bindings.containsKey(13));
    }
}
