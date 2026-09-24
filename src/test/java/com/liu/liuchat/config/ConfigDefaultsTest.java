package com.liu.liuchat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {
    @Test void keepsCustomValuesAndFillsOnlyMissingKeys() {
        YamlConfiguration local = new YamlConfiguration();
        local.set("chat.default.format.player.text", "custom");
        local.set("chat.default.format.custom.text", "extra");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("chat.default.format.player.text", "default");
        defaults.set("chat.default.format.msg.text", "${message}");
        assertTrue(ConfigDefaults.merge(local, defaults));
        assertEquals("custom", local.getString("chat.default.format.player.text"));
        assertEquals("extra", local.getString("chat.default.format.custom.text"));
        assertEquals("${message}", local.getString("chat.default.format.msg.text"));
        assertFalse(ConfigDefaults.merge(local, defaults));
    }
}
