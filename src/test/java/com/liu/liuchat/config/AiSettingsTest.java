package com.liu.liuchat.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test void bundledReviewKeywordsUseTwoLists() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            var match = assertInstanceOf(List.class, defaults.get("ai.review.keywords.match"));
            var all = assertInstanceOf(List.class, defaults.get("ai.review.keywords.all"));
            assertTrue(match.stream().allMatch(String.class::isInstance));
            assertTrue(all.stream().allMatch(group -> group instanceof List<?> terms
                    && !terms.isEmpty() && terms.stream().allMatch(String.class::isInstance)));
        }
    }

    @Test void bundledAssistantNamingDefaultsExist() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("聊天助手", defaults.getString("ai.assistant.name"));
            assertTrue(defaults.getBoolean("ai.assistant.answer-prefix"));
            assertEquals("", defaults.getString("ai.assistant.profiles.bot.name"));
        }
    }

    @Test void bundledAnswerMessageSplitsPrefixAndBody() throws Exception {
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("&b[${name}] ", defaults.getString("ai.answer-prefix"));
            assertEquals("&f${answer}", defaults.getString("ai.answer-text"));
        }
    }
}
