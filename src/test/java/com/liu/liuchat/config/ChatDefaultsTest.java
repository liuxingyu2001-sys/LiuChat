package com.liu.liuchat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDefaultsTest {

    private YamlConfiguration resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(
                    Objects.requireNonNull(input), StandardCharsets.UTF_8));
        }
    }

    @Test void bundledItemShowcaseSupportsMultipleItems() throws Exception {
        var defaults = resource("/chat.yml");
        assertEquals("[i]", defaults.getString("item.format"));
        assertTrue(defaults.getBoolean("item.slots"));
        assertTrue(defaults.getBoolean("item.armor"));
        assertTrue(defaults.getBoolean("item.offhand"));
        assertEquals("&7, ", defaults.getString("item.separator"));
        assertEquals("&e[ ${item} ]", defaults.getString("item.multi-content"));
        assertEquals(14, defaults.getInt("item.max-count"));
        assertEquals("&7物品展示 &e[ ${item} &e]&7", defaults.getString("item.content"));
        assertEquals(18, defaults.getInt("item.length"));
    }

    /** chat.yml 是每服本地文件：已有服务器靠 merge 补齐新键，且不能覆盖用户改过的样式。 */
    @Test void newKeysMergeIntoExistingConfigWithoutOverwritingLocalStyle() throws Exception {
        var defaults = resource("/chat.yml");
        YamlConfiguration local = new YamlConfiguration();
        local.set("item.enable", true);
        local.set("item.format", "[i]");
        local.set("item.content", "自定义样式");

        assertTrue(ConfigDefaults.merge(local, defaults, "chat.yml"));

        assertEquals("自定义样式", local.getString("item.content"));
        assertEquals("&7, ", local.getString("item.separator"));
        assertEquals("&e[ ${item} ]", local.getString("item.multi-content"));
        assertEquals(14, local.getInt("item.max-count"));
        assertTrue(local.getBoolean("item.slots"));
        assertTrue(local.getBoolean("item.armor"));
        assertTrue(local.getBoolean("item.offhand"));
    }
}
