package com.liu.liuchat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageManagerTest {

    @Test void ampersandEscapeKeepsUsageExampleLiteral() {
        String out = MessageManager.colorize("&c用法: /liuc chatcolor <&&a|&&#RRGGBB|off>");
        assertTrue(out.startsWith("§c用法"), out);
        assertTrue(out.contains("<&a|&#RRGGBB|off>"), out);
        assertFalse(out.contains("&&"), out);
        assertFalse(out.contains("§a|"), out);
    }

    @Test void angleEscapeKeepsGradientExampleLiteral() {
        String out = MessageManager.colorize("&7例: <<gradient:#FF0000:#00FF00>>");
        assertTrue(out.startsWith("§7例"), out);
        assertTrue(out.contains("<gradient:#FF0000:#00FF00>"), out);
        // 未转义的普通 MiniMessage/占位标签仍然照常解析
        assertTrue(MessageManager.colorize("&l粗体").startsWith("§l粗体"));
    }

    @Test void plainLegacyCodesStillTranslate() {
        String out = MessageManager.colorize("&8[&bLiuChat&8]&r ");
        assertTrue(out.contains("§bLiuChat"), out);
        assertFalse(out.contains("&b"), out);
    }
}
