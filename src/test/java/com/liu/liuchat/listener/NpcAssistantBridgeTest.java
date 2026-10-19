package com.liu.liuchat.listener;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcAssistantBridgeTest {
    @Test void bindsOnlyConfiguredEnabledNpcIds() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                npcs:
                  '12':
                    assistant: bot
                    title: Guide
                  '13':
                    enable: false
                    assistant: bot
                  invalid:
                    assistant: bot
                  '14':
                    assistant: '../private'
                """);
        var bindings = NpcAssistantBridge.parseBindings(config, true);
        assertEquals(1, bindings.size());
        assertTrue(bindings.containsKey(12));
        assertFalse(bindings.containsKey(13));
    }

    @Test void inheritsDefaultSharedMemoryAndAllowsPerNpcOverride() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                npcs:
                  '12':
                    assistant: bot
                  '13':
                    assistant: bot
                    shared-memory: false
                  '14':
                    assistant: bot
                    shared-memory: true
                """);
        var shared = NpcAssistantBridge.parseBindings(config, true);
        assertTrue(shared.get(12).sharedMemory(), "没写开关的 NPC 继承默认值");
        assertFalse(shared.get(13).sharedMemory(), "单个 NPC 可以关掉共享记忆");
        assertTrue(shared.get(14).sharedMemory(), "单个 NPC 也可以强制开共享记忆");

        var privateByDefault = NpcAssistantBridge.parseBindings(config, false);
        assertFalse(privateByDefault.get(12).sharedMemory());
        assertFalse(privateByDefault.get(13).sharedMemory());
        assertTrue(privateByDefault.get(14).sharedMemory(), "per-NPC 显式 true 覆盖默认 false");
    }

    @Test void sharedSwitchDoesNotAffectBindingRules() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                shared-memory: false
                npcs:
                  '7':
                    enable: false
                    assistant: bot
                  '8':
                    assistant: bad/name
                    shared-memory: true
                """);
        assertTrue(NpcAssistantBridge.parseBindings(config, true).isEmpty(),
                "enable=false 与非法助手名仍然不绑定");
    }
}
