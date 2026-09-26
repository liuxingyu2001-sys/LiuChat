package com.liu.liuchat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageManagerTest {

    @Test void ampersandEscapeKeepsUsageExampleLiteral() {
        String out = MessageManager.colorize("&c用法: /liuc chatcolor <&&a|&&#RRGGBB|off>");
        assertTrue(out.startsWith("§c用法"), out);
        assertTrue(out.contains("<&a|&#RRGGBB|off>"), out);
        assertFalse(out.contains("&&"), out);
        assertFalse(out.contains("§a|"), out);
    }

    @Test void angleEscapeKeepsGradientExampleLiteral() {
        String out = MessageManager.colorize("&7例: <<gradient:#FF0000:#00FF00>>");
        assertTrue(out.startsWith("§7例"), out);
        assertTrue(out.contains("<gradient:#FF0000:#00FF00>"), out);
        // 未转义的普通 MiniMessage/占位标签仍然照常解析
        assertTrue(MessageManager.colorize("&l粗体").startsWith("§l粗体"));
    }

    @Test void plainLegacyCodesStillTranslate() {
        String out = MessageManager.colorize("&8[&bLiuChat&8]&r ");
        assertTrue(out.contains("§bLiuChat"), out);
        assertFalse(out.contains("&b"), out);
    }

    @Test void bundledPrefixSwitchAndHelpTemplateExist() throws Exception {
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            // 前缀开关和帮助行模板都得在语言文件里，否则老文件合并不到、改动不生效
            assertTrue(defaults.getBoolean("prefix-enable"));
            assertTrue(defaults.contains("prefix"));
            assertTrue(defaults.getString("help.cmd").contains("${command}"),
                    String.valueOf(defaults.getString("help.cmd")));
            assertFalse(defaults.contains("help.line"));
            assertTrue(defaults.getString("ai.blocked-sender-notice").contains("已被服务器拦截"));
        }
        try (var input = getClass().getResourceAsStream("/plugin.yml")) {
            var plugin = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(plugin.getBoolean("permissions.liuchat.moderation.blocked-notice.default"));
        }
    }
}
