package com.liu.liuchat.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiuChatExpansionTest {
    @Test void nickFallsBackToPlayerNameOnlyWhenUnset() {
        assertEquals("Alice", LiuChatExpansion.nick("", "Alice"));
        assertEquals("Alice", LiuChatExpansion.nick("  ", "Alice"));
        assertEquals("§aHero", LiuChatExpansion.nick("§aHero", "Alice"));
    }
}
