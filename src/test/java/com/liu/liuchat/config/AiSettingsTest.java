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

    private YamlConfiguration resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) {
            return YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void bundledTokenSavingDefaultsExist() throws Exception {
        var defaults = resource("/ai.yml");
        assertEquals(1024, defaults.getInt("assistant.max-tokens"));
        assertEquals(300, defaults.getInt("assistant.cache-seconds"));
        assertEquals(20, defaults.getInt("assistant.history-messages"));
        assertEquals(4000, defaults.getInt("assistant.history-chars"));
        assertEquals(0, defaults.getInt("assistant.history-seconds"));
        assertTrue(defaults.getBoolean("assistant.history-persist"));
    }

    @Test void sharedChatFilterKeywordsUseTwoLists() throws Exception {
        var config = resource("/config.yml");
        var match = assertInstanceOf(List.class, config.get("chat-filter.keywords.match"));
        var all = assertInstanceOf(List.class, config.get("chat-filter.keywords.all"));
        assertTrue(match.stream().allMatch(String.class::isInstance));
        assertTrue(all.stream().allMatch(group -> group instanceof List<?> terms
                && !terms.isEmpty() && terms.stream().allMatch(String.class::isInstance)));
    }

    @Test void bundledAiAndSharedFilterAreSeparated() throws Exception {
        var ai = resource("/ai.yml");
        assertFalse(ai.getBoolean("migrated"));
        assertTrue(ai.contains("enable"));
        assertTrue(ai.contains("review.enable"));
        assertTrue(ai.contains("assistant.profiles.bot.skill"));
        assertTrue(ai.contains("chat.format"));
        // 屏蔽词是共享策略，不应再留在本地 AI 配置里
        assertFalse(ai.contains("review.keywords"));
        assertFalse(ai.contains("review.contacts"));
        assertFalse(ai.contains("review.block-ips"));
        var config = resource("/config.yml");
        assertTrue(config.contains("chat-filter.enable"));
        assertTrue(config.contains("chat-filter.keywords.match"));
        assertTrue(config.contains("chat-filter.block-domains"));
        assertFalse(config.contains("ai"));
    }

    @Test void bundledAssistantNamingDefaultsExist() throws Exception {
        var defaults = resource("/ai.yml");
        assertEquals("聊天助手", defaults.getString("assistant.name"));
        assertTrue(defaults.getBoolean("assistant.answer-prefix"));
        assertEquals("", defaults.getString("assistant.profiles.bot.name"));
    }

    @Test void bundledKeywordTriggerDefaultsExist() throws Exception {
        var defaults = resource("/ai.yml");
        assertTrue(defaults.getBoolean("chat.keyword-trigger.enable"));
        assertTrue(defaults.getBoolean("chat.keyword-trigger.mention-player"));
    }

    @Test void bundledAnswerMessageSplitsPrefixAndBody() throws Exception {
        var defaults = resource("/messages.yml");
        assertEquals("&b[${name}] ", defaults.getString("ai.answer-prefix"));
        assertEquals("&f${answer}", defaults.getString("ai.answer-text"));
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

    @Test void legacyAiMigrationKeepsAiAndSkipsSharedFilter() {
        YamlConfiguration ai = new YamlConfiguration();
        ai.set("migrated", false);
        ai.set("url", "bundled-default");
        ai.set("assistant.name", "聊天助手");
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("ai.url", "https://old.example/v1");
        legacy.set("ai.model", "old-model");
        legacy.set("ai.review.enable", true);
        legacy.set("ai.review.keywords.match", List.of("bad"));
        legacy.set("ai.review.contacts", false);
        legacy.set("ai.review.block-ips", false);
        legacy.set("ai.assistant.profiles.bot.skill", "custom");
        assertTrue(ConfigManager.applyLegacyAi(ai, legacy.getConfigurationSection("ai")));
        assertEquals("https://old.example/v1", ai.getString("url"));
        assertEquals("old-model", ai.getString("model"));
        assertTrue(ai.getBoolean("review.enable"));
        assertEquals("custom", ai.getString("assistant.profiles.bot.skill"));
        assertEquals("聊天助手", ai.getString("assistant.name"));
        // 屏蔽词改由共享 config.yml 的 chat-filter 承接，不再搬进 ai.yml
        assertFalse(ai.contains("review.keywords"));
        assertFalse(ai.contains("review.contacts"));
        assertFalse(ai.contains("review.block-ips"));
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
