package com.liu.liuchat.hook.qq;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqBotSettingsTest {

    @Test
    void defaultsToGroupAndC2cIntents() {
        QqBotSettings settings = QqBotSettings.load(new YamlConfiguration());
        assertFalse(settings.usable());
        assertEquals(QqBotSettings.INTENT_GROUP_AND_C2C, settings.intents());
        assertTrue(settings.receiveType(QqTarget.Type.GROUP));
        assertTrue(settings.receiveType(QqTarget.Type.C2C));
        assertFalse(settings.receiveType(QqTarget.Type.CHANNEL));
        assertEquals(List.of(), settings.sendTargets());
    }

    @Test
    void parsesCredentialsIntentsAndTargets() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enable", true);
        config.set("app-id", " 102012345 ");
        config.set("app-secret", "secret");
        config.set("intents", List.of("guild", "direct"));
        config.set("intents-bits", "0x4000000");
        config.set("send.targets", List.of("group:g1", "c2c:u1", "channel:C1",
                "direct:D1", "plain-default", "bogus:x", "group:"));
        QqBotSettings settings = QqBotSettings.load(config);
        assertTrue(settings.usable());
        assertEquals("102012345", settings.appId());
        assertTrue(settings.receiveType(QqTarget.Type.CHANNEL));
        assertTrue(settings.receiveType(QqTarget.Type.DIRECT));
        assertFalse(settings.receiveType(QqTarget.Type.GROUP));
        assertEquals(QqBotSettings.INTENT_PUBLIC_GUILD_MESSAGES
                | QqBotSettings.INTENT_DIRECT_MESSAGE | 0x4000000, settings.intents());
        assertEquals(5, settings.sendTargets().size());
        assertEquals(new QqTarget(QqTarget.Type.GROUP, "g1"), settings.sendTargets().get(0));
        assertEquals(new QqTarget(QqTarget.Type.C2C, "u1"), settings.sendTargets().get(1));
        assertEquals(new QqTarget(QqTarget.Type.CHANNEL, "C1"), settings.sendTargets().get(2));
        assertEquals(new QqTarget(QqTarget.Type.DIRECT, "D1"), settings.sendTargets().get(3));
        assertEquals(new QqTarget(QqTarget.Type.GROUP, "plain-default"), settings.sendTargets().get(4));
    }

    @Test
    void clampsUnsafeValuesAndDetectsConnectionChanges() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enable", true);
        config.set("app-id", "1");
        config.set("app-secret", "2");
        config.set("connection.timeout-seconds", 9999);
        config.set("connection.reconnect-seconds", 0);
        config.set("receive.max-length", -5);
        config.set("send.interval-ms", -1);
        QqBotSettings first = QqBotSettings.load(config);
        assertEquals(120, first.timeoutSeconds());
        assertEquals(1, first.reconnectSeconds());
        assertEquals(1, first.receiveMaxLength());
        assertEquals(0, first.sendIntervalMs());
        config.set("app-id", "3");
        assertNotEquals(first.fingerprint(), QqBotSettings.load(config).fingerprint());
        config.set("app-id", "1");
        config.set("receive.format", "changed");
        assertEquals(first.fingerprint(), QqBotSettings.load(config).fingerprint());
    }

    @Test
    void bundledTemplateIsParsableAndDisabledByDefault() throws Exception {
        try (var input = getClass().getResourceAsStream("/qq.yml")) {
            YamlConfiguration config = new YamlConfiguration();
            config.load(new InputStreamReader(java.util.Objects.requireNonNull(input), StandardCharsets.UTF_8));
            QqBotSettings settings = QqBotSettings.load(config);
            assertFalse(settings.enabled());
            assertFalse(settings.usable());
            assertEquals(QqBotSettings.INTENT_GROUP_AND_C2C, settings.intents());
            assertEquals(QqBotText.DEFAULT_RECEIVE_FORMAT, settings.receiveFormat());
            assertFalse(settings.sendEnabled());
        }
    }
}
