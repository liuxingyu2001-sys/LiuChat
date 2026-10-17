package com.liu.liuchat.hook.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqBotTextTest {

    @Test
    void removesChannelMentionTags() {
        assertEquals("你好 世界", QqBotText.cleanIncoming("<@!123> 你好 <@456> 世界", 300));
        assertEquals("", QqBotText.cleanIncoming(null, 300));
    }

    @Test
    void incomingMessageIsInsertedAfterColoringSoItCannotInjectColors() {
        String line = QqBotText.incomingLine("&b${sender}&7: &f${message}", "小明",
                "&c红色 §l加粗", "群聊", "group-1", 300);
        assertTrue(line.startsWith("§b小明§7: §f"));
        assertTrue(line.contains("&c红色 加粗"), line);
        assertFalse(line.contains("§c红色"), line);
    }

    @Test
    void incomingTemplateSupportsSourceAndTarget() {
        String line = QqBotText.incomingLine("[${source}/${target}] ${sender}: ${message}",
                "A", "hi", "群聊", "g1", 300);
        assertEquals("[群聊/g1] A: hi", line);
    }

    @Test
    void outgoingMessageStripsMinecraftColorsAndNewlines() {
        assertEquals("[srv] Alex: hello world", QqBotText.outgoingLine(
                "[${server}] ${player}: ${message}", "srv", "Alex", "§ahello\n§lworld", 200));
    }

    @Test
    void outgoingAppendsMessageWhenTemplateOmitsIt() {
        assertEquals("Alex says hi", QqBotText.outgoingLine("${player} says", "srv", "Alex", "hi", 200));
    }

    @Test
    void truncationKeepsCodePointsIntact() {
        String text = QqBotText.cleanIncoming("😀😀😀😀", 3);
        assertEquals("😀😀…", text);
    }

    @Test
    void fallbackSenderUsesStableShortSuffix() {
        assertEquals("QQ用户·ABCDEF", QqBotText.fallbackSender("QQ用户", "0123456789ABCDEF"));
        assertEquals("QQ用户", QqBotText.fallbackSender("", ""));
        assertEquals("QQ用户·123", QqBotText.fallbackSender(null, "123"));
    }
}
