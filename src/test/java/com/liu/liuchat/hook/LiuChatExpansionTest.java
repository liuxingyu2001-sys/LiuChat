package com.liu.liuchat.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiuChatExpansionTest {
    @Test void nickFallsBackToPlayerNameOnlyWhenUnset() {
        assertEquals("Alice", LiuChatExpansion.nick("", "Alice"));
        assertEquals("Alice", LiuChatExpansion.nick("  ", "Alice"));
        assertEquals("§aHero", LiuChatExpansion.nick("§aHero", "Alice"));
    }

    @Test void hornBalanceShowsDashWhenStorageUnavailable() {
        assertEquals("-", LiuChatExpansion.balanceText(-1));
        assertEquals("0", LiuChatExpansion.balanceText(0));
        assertEquals("12", LiuChatExpansion.balanceText(12));
    }
}
