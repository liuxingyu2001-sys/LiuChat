package com.liu.liuchat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SharedConfigTest {
    @TempDir Path temp;
    private final Logger logger = Logger.getAnonymousLogger();

    @Test void resolvesExplicitAutomaticAndLocalRoots() throws Exception {
        Path local = temp.resolve("local");
        Path automatic = temp.resolve("automatic");
        assertEquals(local, SharedConfig.resolveRoot(local, "", automatic, logger));
        assertFalse(Files.exists(automatic));
        Files.createDirectories(automatic);
        assertEquals(automatic, SharedConfig.resolveRoot(local, "", automatic, logger));
        Path explicit = temp.resolve("explicit");
        assertEquals(explicit, SharedConfig.resolveRoot(local, explicit.toString(), automatic, logger));
        assertTrue(Files.isDirectory(explicit));
        Path blocked = temp.resolve("file");
        Files.writeString(blocked, "blocked");
        assertEquals(local, SharedConfig.resolveRoot(local, blocked.toString(), automatic, logger));
    }

    @Test void detectsChangesToOlderFilesAndDeletions() throws Exception {
        Path first = temp.resolve("config.yml");
        Path second = temp.resolve("chat.yml");
        Files.writeString(first, "value: 1");
        Files.writeString(second, "value: 1");
        Files.setLastModifiedTime(first, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(second, FileTime.fromMillis(10000));
        var before = SharedConfig.snapshot(temp);
        Files.setLastModifiedTime(first, FileTime.fromMillis(2000));
        var changed = SharedConfig.snapshot(temp);
        assertNotEquals(before, changed);
        Files.delete(first);
        assertNotEquals(changed, SharedConfig.snapshot(temp));
    }

    @Test void watchesSkillAdditionsAndIgnoresRuntimeData() throws Exception {
        var before = SharedConfig.snapshot(temp);
        Files.writeString(temp.resolve("ai-sessions.json"), "{}");
        Files.writeString(temp.resolve("unrelated.yml"), "value: 1");
        assertEquals(before, SharedConfig.snapshot(temp));
        Path skill = temp.resolve("skills/bot/nested/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "Instructions");
        assertNotEquals(before, SharedConfig.snapshot(temp));
        Files.delete(skill);
        assertEquals(before, SharedConfig.snapshot(temp));
    }

    @Test void watchesIndependentSkillsDirectory() throws Exception {
        Path root = temp.resolve("config");
        Path skills = temp.resolve("external-skills");
        Files.createDirectories(root);
        var before = SharedConfig.snapshot(root, skills);
        Files.createDirectories(skills.resolve("bot"));
        Files.writeString(skills.resolve("bot/SKILL.md"), "Instructions");
        assertNotEquals(before, SharedConfig.snapshot(root, skills));
        Files.writeString(root.resolve("reminders.yml"), "enable: false");
        assertTrue(SharedConfig.snapshot(root, skills).containsKey("reminders.yml"));
    }

    @Test void automaticReloadDefaultsToDisabled() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var config = new YamlConfiguration();
            config.load(new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(config.contains("settings.auto-reload-config"));
            assertFalse(config.getBoolean("settings.auto-reload-config"));
            assertEquals("", config.getString("settings.shared-config-path"));
        }
    }
}
