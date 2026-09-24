package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicChatAiServiceTest {

    @Test void fixedFormatReplacesOnlyNameAndMessage() {
        String line = PublicChatAiService.formatLine("&7[AI] &b${player}&7: &f${message}", "小派蒙", "大家好");
        assertEquals("§7[AI] §b小派蒙§7: §f大家好", line);
    }

    @Test void placeholdersAndColorCodesInMessageStayRaw() {
        // 假人拿不到占位符：PAPI 变量、其他插件占位符、${player}、颜色码一律不解析，原样显示
        String line = PublicChatAiService.formatLine("&f${player}: &f${message}", "小派蒙",
                "%player_level% ${player} &7x");
        assertEquals("§f小派蒙: §f%player_level% ${player} &7x", line);
    }

    @Test void blankFormatFallsBackToDefault() {
        assertEquals("§7[AI] §b小派蒙§7: §fhi", PublicChatAiService.formatLine("", "小派蒙", "hi"));
        assertEquals("§7[AI] §b小派蒙§7: §fhi", PublicChatAiService.formatLine(null, "小派蒙", "hi"));
    }

    @Test void nullNameOrMessageTolerated() {
        assertEquals("§7[AI] §b§7: §f", PublicChatAiService.formatLine(null, null, null));
    }
}
