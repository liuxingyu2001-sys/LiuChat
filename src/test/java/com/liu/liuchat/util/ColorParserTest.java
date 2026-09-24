package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorParserTest {
    @Test void legacyAndMiniMessageTogether() {
        String output = ColorParser.parse("&aHello <bold>world</bold>");
        assertTrue(output.contains("§aHello"));
        assertTrue(output.contains("§lworld"));
    }

    @Test void rgbLegacyAndMiniMessage() {
        String output = ColorParser.parse("&#12AB34Hi <#ff0099>X");
        assertTrue(output.contains("§x§1§2§a§b§3§4"));
        assertTrue(output.toLowerCase().contains("§x§f§f§0§0§9§9"));
    }

    @Test void playerInputDoesNotCreateClickEvents() {
        String output = ColorParser.playerText("&aHi <click:run_command:'/op Alice'>click</click>");
        assertTrue(output.contains("§aHi"));
        assertTrue(output.contains("<click:"));
    }

    @Test void nullInput() {
        assertEquals(null, ColorParser.parse(null));
    }
}
