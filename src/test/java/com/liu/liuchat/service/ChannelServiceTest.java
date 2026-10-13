package com.liu.liuchat.service;

import com.liu.liuchat.api.ChatChannel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ChannelServiceTest {
    @Test void bundledChannelsHaveDistinctAudienceScopes() throws Exception {
        var yaml = new YamlConfiguration();
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/channels.yml"), StandardCharsets.UTF_8)) {
            yaml.load(in);
        }
        var parsed = ChannelService.parse(yaml.getConfigurationSection("channels"));
        assertEquals(ChatChannel.Scope.GLOBAL, parsed.get(yaml.getString("default")).scope());
        assertEquals(ChatChannel.Scope.NEARBY, parsed.get("nearby").scope());
        assertEquals(100, parsed.get("nearby").radius());
        assertEquals(ChatChannel.Scope.MEMBERS, parsed.get("staff").scope());
        assertEquals("liuchat.channel.staff", parsed.get("staff").receivePermission());
        assertThrows(UnsupportedOperationException.class, () -> parsed.clear());
    }
    @Test void invalidScopeAndRadiusFailBeforeReplacingConfiguration() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("channels:\n  test:\n    scope: WRONG\n");
        assertThrows(IllegalArgumentException.class, () -> ChannelService.parse(yaml.getConfigurationSection("channels")));
        yaml.set("channels.test.scope", "NEARBY");
        yaml.set("channels.test.radius", -1);
        assertThrows(IllegalArgumentException.class, () -> ChannelService.parse(yaml.getConfigurationSection("channels")));
        yaml.set("channels.test.radius", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> ChannelService.parse(yaml.getConfigurationSection("channels")));
    }
    @Test void rejectsCaseInsensitiveDuplicateAndInvalidId() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("channels:\n  Test:\n    name: One\n  test:\n    name: Two\n");
        assertThrows(IllegalArgumentException.class, () -> ChannelService.parse(yaml.getConfigurationSection("channels")));
        assertThrows(IllegalArgumentException.class, () -> new ChatChannel("bad id", "Test", ChatChannel.Scope.SERVER, "", "", "", 10));
    }
    @Test void insertedTextCannotInjectOtherTemplateVariables() {
        assertEquals("${message}: hello ${player}", ChannelFormat.apply("${nick}: ${message}",
                java.util.Map.of("nick", "${message}", "message", "hello ${player}")));
        assertEquals("$5\\test", ChannelFormat.apply("${message}", java.util.Map.of("message", "$5\\test")));
    }
    @Test void channelDefinitionsNormalizeIdAndRejectMissingFields() {
        assertEquals("staff", new ChatChannel("STAFF", "Staff", ChatChannel.Scope.MEMBERS, "", "", "", 10).id());
        assertThrows(IllegalArgumentException.class, () -> new ChatChannel("global", "Public", ChatChannel.Scope.GLOBAL, "", "staff", "", 10));
        assertThrows(NullPointerException.class, () -> new ChatChannel("test", "Test", null, "", "", "", 10));
        assertThrows(IllegalArgumentException.class, () -> ChannelService.parse(null));
    }
}
