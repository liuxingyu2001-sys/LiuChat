package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileChatColorTest {
    @Test void colorsWholeMessageAndPreservesItemToken() {
        String result = ProfileChatColor.apply("<gradient:#1AFFF0:#2EA4FF>&l", "你好[i]世界", "[i]");
        assertTrue(result.startsWith("§x§1§A§F§F§F§0§l你"), result);
        assertTrue(result.contains("[i]"));
        assertTrue(result.endsWith("§x§2§E§A§4§F§F§l界"));
        assertFalse(result.contains("<gradient:"));
    }

    @Test void keepsPlayerMiniMessageLiteral() {
        String result = ProfileChatColor.apply("<gradient:#000000:#FFFFFF>", "<click:run_command:'/op me'>", "[i]");
        assertTrue(result.contains("<"));
        assertFalse(result.contains("§k"));
    }
}
