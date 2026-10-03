package com.liu.liuchat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsTest {
    @Test void assistantInheritsSharedSettingsWhenBlank() {
        assertEquals("mimo-v2.6-flash", ConfigManager.assistantValue("", "mimo-v2.6-flash"));
        assertEquals("shared-key", ConfigManager.assistantValue(null, "shared-key"));
        assertEquals("assistant-model", ConfigManager.assistantValue("assistant-model", "shared-model"));
    }

    private YamlConfiguration bundled() throws Exception {
        try (var input = getClass().getResourceAsStream("/ai.yml")) {
            return YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void bundledTokenSavingDefaultsExist() throws Exception {
        var defaults = bundled();
        assertEquals(1024, defaults.getInt("assistant.max-tokens"));
        assertEquals(300, defaults.getInt("assistant.cache-seconds"));
        assertEquals(20, defaults.getInt("assistant.history-messages"));
        assertEquals(4000, defaults.getInt("assistant.history-chars"));
        assertEquals(0, defaults.getInt("assistant.history-seconds"));
        assertTrue(defaults.getBoolean("assistant.history-persist"));
    }

    @Test void bundledReviewKeywordsUseTwoLists() throws Exception {
        var defaults = bundled();
        var match = assertInstanceOf(List.class, defaults.get("review.keywords.match"));
        var all = assertInstanceOf(List.class, defaults.get("review.keywords.all"));
        assertTrue(match.stream().allMatch(String.class::isInstance));
        assertTrue(all.stream().allMatch(group -> group instanceof List<?> terms
                && !terms.isEmpty() && terms.stream().allMatch(String.class::isInstance)));
    }

    @Test void bundledAssistantNamingDefaultsExist() throws Exception {
        var defaults = bundled();
        assertEquals("聊天助手", defaults.getString("assistant.name"));
        assertTrue(defaults.getBoolean("assistant.answer-prefix"));
        assertEquals("", defaults.getString("assistant.profiles.bot.name"));
    }

    @Test void bundledKeywordTriggerDefaultsExist() throws Exception {
        var defaults = bundled();
        assertTrue(defaults.getBoolean("chat.keyword-trigger.enable"));
        assertTrue(defaults.getBoolean("chat.keyword-trigger.mention-player"));
    }

    @Test void bundledAnswerMessageSplitsPrefixAndBody() throws Exception {
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            var defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("&b[${name}] ", defaults.getString("ai.answer-prefix"));
            assertEquals("&f${answer}", defaults.getString("ai.answer-text"));
        }
    }

    @Test void bundledAiFileCoversAllSectionsAndConfigNoLongerShipsAi() throws Exception {
        var ai = bundled();
        assertFalse(ai.getBoolean("migrated"));
        assertTrue(ai.contains("enable"));
        assertTrue(ai.contains("url"));
        assertTrue(ai.contains("review.keywords.match"));
        assertTrue(ai.contains("assistant.profiles.bot.skill"));
        assertTrue(ai.contains("chat.format"));
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var config = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(config.contains("ai"));
        }
    }

    @Test void localAiFileIsMountedUnderTheAiSection() {
        YamlConfiguration ai = new YamlConfiguration();
        ai.set("enable", true);
        ai.set("url", "https://example/v1");
        ai.set("assistant.profiles.bot.skill", "bot");
        ai.set("chat.keywords", List.of());
        ai.set("chat.chance", 0.0);
        YamlConfiguration config = new YamlConfiguration();
        ConfigManager.mountAi(config, "ai.", ai);
        assertTrue(config.getBoolean("ai.enable"));
        assertEquals("https://example/v1", config.getString("ai.url"));
        assertEquals("bot", config.getString("ai.assistant.profiles.bot.skill"));
        assertTrue(config.getStringList("ai.chat.keywords").isEmpty());
        assertEquals(0.0, config.getDouble("ai.chat.chance"));
    }

    @Test void legacyAiSectionMigratesOnceIntoLocalFile() {
        YamlConfiguration ai = new YamlConfiguration();
        ai.set("migrated", false);
        ai.set("url", "bundled-default");
        ai.set("assistant.name", "聊天助手");
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("ai.url", "https://old.example/v1");
        legacy.set("ai.model", "old-model");
        legacy.set("ai.review.keywords.match", List.of("bad"));
        legacy.set("ai.assistant.profiles.bot.skill", "custom");
        assertTrue(ConfigManager.applyLegacyAi(ai, legacy.getConfigurationSection("ai")));
        assertEquals("https://old.example/v1", ai.getString("url"));
        assertEquals("old-model", ai.getString("model"));
        assertEquals(List.of("bad"), ai.getStringList("review.keywords.match"));
        assertEquals("custom", ai.getString("assistant.profiles.bot.skill"));
        assertEquals("聊天助手", ai.getString("assistant.name"));
        assertTrue(ai.getBoolean("migrated"));
        assertFalse(ConfigManager.applyLegacyAi(ai, legacy.getConfigurationSection("ai")));
    }

    @Test void migrationWithoutLegacySectionStillMarksDone() {
        YamlConfiguration ai = new YamlConfiguration();
        ai.set("url", "");
        assertTrue(ConfigManager.applyLegacyAi(ai, null));
        assertTrue(ai.getBoolean("migrated"));
    }
}
