package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileChatColorTest {
    @Test void colorsWholeMessageAndPreservesItemToken() {
        String result = ProfileChatColor.apply("<gradient:#1AFFF0:#2EA4FF>&l", "你好[i]世界", "[i]");
        assertTrue(result.startsWith("§x§1§A§F§F§F§0§l你"), result);
        assertTrue(result.contains("[i]"));
        assertTrue(result.contains("§r[i]"));
        assertTrue(result.endsWith("§x§2§E§A§4§F§F§l界"));
        assertFalse(result.contains("<gradient:"));
    }

    @Test void leavesMentionsEmojiAndUrlsUncoloredAndContiguous() {
        String result = ProfileChatColor.apply("<gradient:#1AFFF0:#2EA4FF>",
                "你好 @Alice :age: [i] https://www.mc99.top 再见", "[i]");
        assertTrue(result.contains("§r@Alice"), result);
        assertTrue(result.contains("§r:age:"), result);
        assertTrue(result.contains("§r[i]"), result);
        assertTrue(result.contains("§rhttps://www.mc99.top"), result);
        assertTrue(result.contains("§x§2§E§A§4§F§F见"), result);
        assertFalse(result.contains("§x§1§A§F§F§F§0§r"), result);
    }

    @Test void preservesSpecialTokensForDownstreamParsers() {
        String text = ProfileChatColor.apply("<gradient:#1AFFF0:#2EA4FF>",
                "@Alice :age: [i] https://www.mc99.top", "[i]");
        var mention = com.liu.liuchat.util.Mentions.mark(text, java.util.List.of("Alice"), true, "§b");
        assertTrue(mention.mentions("Alice"));
        assertTrue(mention.text().contains("§b@Alice"), mention.text());
        assertTrue(mention.text().contains(":age:"), mention.text());
        assertTrue(mention.text().contains("[i]"), mention.text());
        assertTrue(mention.text().contains("https://www.mc99.top"), mention.text());
    }

    @Test void keepsPlayerMiniMessageLiteral() {
        String result = ProfileChatColor.apply("<gradient:#000000:#FFFFFF>", "<click:run_command:'/op me'>", "[i]");
        assertTrue(result.contains("<"));
        assertFalse(result.contains("§k"));
    }
}
