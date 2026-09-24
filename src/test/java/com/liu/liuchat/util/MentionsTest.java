package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionsTest {

    @Test void barePlayerIdBecomesMention() {
        Mentions.Marked marked = Mentions.mark("hello Steve", List.of("Steve"), true, "§b");
        assertEquals("hello §b@Steve§r", marked.text());
        assertEquals(List.of("Steve"), marked.names());
    }

    @Test void keepAtFalseDropsSymbol() {
        Mentions.Marked marked = Mentions.mark("hi @Steve", List.of("Steve"), false, "§b");
        assertEquals("hi §bSteve§r", marked.text());
        assertEquals(List.of("Steve"), marked.names());
    }

    @Test void atMentionWorksWithoutKnownNames() {
        Mentions.Marked marked = Mentions.mark("hi @NotOnline", List.of(), true, "§b");
        assertEquals("hi §b@NotOnline§r", marked.text());
        assertEquals(List.of("NotOnline"), marked.names());
    }

    /** 无颜色权限的玩家消息里 & 是字面文本，@ 人依旧要变色 */
    @Test void highlightNeedsNoColorPermission() {
        Mentions.Marked marked = Mentions.mark("&b@Steve", List.of("Steve"), true, "§e");
        assertEquals("&b§e@Steve§r", marked.text());
        assertTrue(marked.mentions("Steve"));
    }

    @Test void restoresSurroundingColorAfterMention() {
        Mentions.Marked marked = Mentions.mark("§6look @Steve now", List.of("Steve"), true, "§b");
        assertEquals("§6look §b@Steve§r§6 now", marked.text());
    }

    @Test void keepsStylesAfterMention() {
        Mentions.Marked marked = Mentions.mark("§lbold Steve!", List.of("Steve"), true, "§b");
        assertEquals("§lbold §b@Steve§r§l!", marked.text());
    }

    @Test void keepsGradientStateAfterMention() {
        StringBuilder raw = new StringBuilder();
        String[] letters = {"h", "i", " ", "S", "t", "e", "v", "e", "!"};
        String[] hexes = {"123456", "234561", "345612", "456123", "561234", "612345", "123456", "234561", "345612"};
        for (int i = 0; i < letters.length; i++) {
            raw.append("§x");
            for (char digit : hexes[i].toCharArray()) raw.append('§').append(digit);
            raw.append(letters[i]);
        }
        Mentions.Marked marked = Mentions.mark(raw.toString(), List.of("Steve"), true, "§b");
        // 被 @ 后续上 "!" 自带的渐变色码，颜色不断层
        assertTrue(marked.text().contains("§b@Steve§r§x§3§4§5§6§1§2!"), marked.text().replace("§", "&"));
        assertEquals(List.of("Steve"), marked.names());
    }

    @Test void noMatchInsideLongerWords() {
        Mentions.Marked marked = Mentions.mark("Stevenson Steve2", List.of("Steve"), true, "§b");
        assertEquals("Stevenson Steve2", marked.text());
        assertEquals(List.of(), marked.names());
    }

    @Test void emailAddressesAreNotMentions() {
        Mentions.Marked marked = Mentions.mark("mail steve@example.com", List.of("steve"), true, "§b");
        assertEquals("mail steve@example.com", marked.text());
    }

    @Test void unknownBareNameIsNotAutoMentioned() {
        Mentions.Marked marked = Mentions.mark("hello Bob", List.of("Steve"), true, "§b");
        assertEquals("hello Bob", marked.text());
    }

    @Test void mentionedNamesAreCaseInsensitiveAndDeduped() {
        Mentions.Marked marked = Mentions.mark("@steve and @Steve", List.of(), true, "§b");
        assertEquals(1, marked.names().size());
        assertTrue(marked.mentions("Steve"));
        assertTrue(marked.mentions("STEVE"));
        assertEquals(List.of("steve"), marked.names());
    }

    @Test void emptyHighlightStillReportsNames() {
        Mentions.Marked marked = Mentions.mark("@Steve", List.of(), true, "");
        assertEquals("@Steve", marked.text());
        assertEquals(List.of("Steve"), marked.names());
    }

    @Test void nullAndEmptyMessages() {
        assertEquals("", Mentions.mark(null, List.of("Steve"), true, "§b").text());
        assertEquals("", Mentions.mark("", List.of("Steve"), true, "§b").text());
    }
}
