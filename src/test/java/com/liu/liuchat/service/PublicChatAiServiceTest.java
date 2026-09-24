package com.liu.liuchat.service;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicChatAiServiceTest {

    @Test void fixedFormatReplacesOnlyNameAndMessage() {
        String line = PublicChatAiService.formatLine("&7[AI] &b${player}&7: &f${message}", "小派蒙", "大家好");
        assertEquals("§7[AI] §b小派蒙§7: §f大家好", line);
    }

    @Test void placeholdersAndColorCodesInMessageStayRaw() {
        // 假人拿不到占位符：PAPI 变量、其他插件占位符、${player}、色码、MiniMessage 标签一律不解析，原样显示
        String line = PublicChatAiService.formatLine("&f${player}: &e${message}", "小派蒙",
                "%player_level% ${player} &7x <gradient:#a:#b>y");
        assertEquals("§f小派蒙: §e%player_level% ${player} &7x <gradient:#a:#b>y", line);
    }

    @Test void gradientAndLegacyColorsMix() {
        String line = PublicChatAiService.formatLine(
                "&e主城 &f[<gradient:#afd9c2:#97e1cb>久久新生&f] &f${player}&7: &f${message}",
                "小派蒙", "大家好");
        assertTrue(line.contains("§e主城 §f["));
        // 渐变序列化为逐字符 §x hex（起始色 #afd9c2）
        assertTrue(line.contains("§x§a§f§d§9§c§2"));
        assertFalse(line.contains("<gradient"));
        // 去掉 §x hex 序列后名字完整、结尾是回复内容
        String plain = line.replaceAll("§x(§.){6}", "");
        assertTrue(plain.contains("久久新生"));
        assertTrue(plain.endsWith("大家好"));
    }

    @Test void blankFormatFallsBackToDefault() {
        assertEquals("§7[AI] §b小派蒙§7: §fhi", PublicChatAiService.formatLine("", "小派蒙", "hi"));
        assertEquals("§7[AI] §b小派蒙§7: §fhi", PublicChatAiService.formatLine(null, "小派蒙", "hi"));
    }

    @Test void headPlaceholderUsesConfiguredUuidAndDoesNotParseReply() {
        UUID skin = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String format = "&e主城 ${head} <gradient:#afd9c2:#97e1cb>久久新生&f ${player}: ${message}";
        String reply = "你好 ${head} %player_level%";
        BaseComponent[] parts = PublicChatAiService.formatComponents(format, "小派蒙", reply, skin);
        assertEquals(1, Arrays.stream(parts).filter(part -> part.getInsertion() != null).count());
        assertTrue(Arrays.stream(parts).anyMatch(part -> ("liuchat-head:" + skin).equals(part.getInsertion())));
        String plain = Arrays.stream(parts).map(part -> part.toPlainText()).reduce("", String::concat);
        assertTrue(plain.contains("你好 ${head} %player_level%"));
        assertFalse(plain.contains("<gradient"));
        assertFalse(plain.contains("liuchat-head:"));
        assertFalse(PublicChatAiService.formatLine(format, "小派蒙", "你好").contains("${head}"));
        assertEquals(0, Arrays.stream(PublicChatAiService.formatComponents(format, "小派蒙", "你好", null))
                .filter(part -> part.getInsertion() != null).count());
    }

    @Test void headKeepsSurroundingColor() {
        UUID skin = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BaseComponent[] parts = PublicChatAiService.formatComponents("&e前${head}后", "小派蒙", "", skin);
        assertTrue(Arrays.stream(parts).anyMatch(part -> part.toPlainText().contains("后")
                && ChatColor.YELLOW.equals(part.getColorRaw())));
    }

    @Test void nullNameOrMessageTolerated() {
        String line = PublicChatAiService.formatLine(null, null, null);
        assertFalse(line.contains("${"));
    }
}
