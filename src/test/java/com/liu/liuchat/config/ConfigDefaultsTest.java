package com.liu.liuchat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test void migratesLegacyHornTemplateIntoOnlyMissingChannelTemplates() {
        YamlConfiguration local = new YamlConfiguration();
        local.set("horn.format", "&dLegacy ${player}: ${message}");
        local.set("horn.title-message-format", "&bCustom title");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("horn.message-format", "chat default");
        defaults.set("horn.title-message-format", "title default");
        defaults.set("horn.actionbar-message-format", "action default");
        assertTrue(ConfigDefaults.merge(local, defaults, "config.yml"));
        assertEquals("&dLegacy ${player}: ${message}", local.getString("horn.message-format"));
        assertEquals("&bCustom title", local.getString("horn.title-message-format"));
        assertEquals("&dLegacy ${player}: ${message}", local.getString("horn.actionbar-message-format"));
    }

    @Test void migratesLegacyAiFilterIntoSharedChatFilterOnce() {
        YamlConfiguration local = new YamlConfiguration();
        local.set("ai.enable", true);
        local.set("ai.review.keywords.match", List.of("bad"));
        local.set("ai.review.contacts", false);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("chat-filter.enable", false);
        defaults.set("chat-filter.keywords.match", List.of("default"));
        defaults.set("chat-filter.block-ips", true);
        assertTrue(ConfigDefaults.merge(local, defaults, "config.yml"));
        assertTrue(local.getBoolean("chat-filter.enable"));
        assertEquals(List.of("bad"), local.getStringList("chat-filter.keywords.match"));
        assertFalse(local.getBoolean("chat-filter.contacts"));
        assertTrue(local.getBoolean("chat-filter.block-ips"));
        // 已有 chat-filter 后不再重复迁移
        assertFalse(ConfigDefaults.merge(local, defaults, "config.yml"));
    }

    @Test void freshConfigDoesNotMigrateFilterAndUsesBundledDefaults() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            defaults.load(new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        YamlConfiguration local = new YamlConfiguration();
        ConfigDefaults.merge(local, defaults, "config.yml");
        assertFalse(local.getBoolean("chat-filter.enable"));
        assertEquals(defaults.getStringList("chat-filter.keywords.match"),
                local.getStringList("chat-filter.keywords.match"));
    }

    @Test void upgradesOldBotTextDefaultsToGrayButKeepsCustomColors() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("ai.answer-text", "&f${answer}");
        assertTrue(ConfigDefaults.merge(messages, new YamlConfiguration(), "messages.yml"));
        assertEquals("&7${answer}", messages.getString("ai.answer-text"));
        assertFalse(ConfigDefaults.merge(messages, new YamlConfiguration(), "messages.yml"));

        YamlConfiguration custom = new YamlConfiguration();
        custom.set("ai.answer-text", "&e${answer}");
        assertFalse(ConfigDefaults.merge(custom, new YamlConfiguration(), "messages.yml"));
        assertEquals("&e${answer}", custom.getString("ai.answer-text"));

        YamlConfiguration ai = new YamlConfiguration();
        ai.set("chat.format", "&7[AI] &b${player}&7: &f${message}");
        assertTrue(ConfigDefaults.merge(ai, new YamlConfiguration(), "ai.yml"));
        assertEquals("&7[AI] &b${player}&7: &7${message}", ai.getString("chat.format"));
    }

    @Test void bundledSocialDefaultsExist() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(100, defaults.getInt("friends.max-friends"));
            assertEquals(168, defaults.getInt("friends.request-expire-hours"));
            assertEquals(300, defaults.getInt("friends.join-notify-cooldown-seconds"));
            assertEquals(30, defaults.getInt("mail.expire-days"));
            assertEquals(3, defaults.getInt("mail.max-attachments"));
            assertFalse(defaults.getBoolean("mail.allow-self"));
        }
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            var messages = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(messages.contains("friend.usage"));
            assertTrue(messages.contains("mail.usage"));
            assertTrue(messages.contains("help.friend.desc"));
            assertTrue(messages.contains("help.mail.desc"));
        }
    }

    @Test void socialMessagesNeverAskPlayersToTypeCommands() throws Exception {
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            var messages = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input), java.nio.charset.StandardCharsets.UTF_8));
            int checked = 0;
            for (String key : messages.getKeys(true)) {
                if (!(key.startsWith("friend.") || key.startsWith("mail."))) continue;
                String text = messages.getString(key);
                if (text == null) continue;
                checked++;
                assertFalse(text.contains("/liuc"), "好友/邮件提示不应包含指令: " + key + " -> " + text);
                assertFalse(text.contains("/friend"), "好友/邮件提示不应包含指令: " + key + " -> " + text);
                assertFalse(text.contains("/mail"), "好友/邮件提示不应包含指令: " + key + " -> " + text);
            }
            assertTrue(checked >= 40, "应覆盖全部好友/邮件提示，实际: " + checked);
        }
    }

    @Test void privateChatDefaultsHaveSeparateReplyActions() throws Exception {
        try (var input = getClass().getResourceAsStream("/chat.yml")) {
            var defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("/tell ${target} ", defaults.getString("private.to.format.player.clickSuggest"));
            assertEquals("/tell ${player} ", defaults.getString("private.from.format.player.clickSuggest"));
            assertEquals("&f${message}", defaults.getString("private.from.format.msg.text"));
            YamlConfiguration local = new YamlConfiguration();
            local.set("private.from.format.player.clickSuggest", "/reply ${player} ");
            assertTrue(ConfigDefaults.merge(local, defaults));
            assertEquals("/reply ${player} ", local.getString("private.from.format.player.clickSuggest"));
            assertEquals("/tell ${target} ", local.getString("private.to.format.player.clickSuggest"));
        }
    }
}
