package com.liu.liuchat.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Friend 快照：新增 lastSeen 后仍保留旧的四参数构造。 */
class FriendTest {

    @Test
    void carriesLastSeen() {
        UUID uuid = UUID.randomUUID();
        Friend friend = new Friend(uuid, "Alice", 100L, true, 200L);
        assertEquals(uuid, friend.uuid());
        assertEquals("Alice", friend.name());
        assertEquals(100L, friend.since());
        assertTrue(friend.online());
        assertEquals(200L, friend.lastSeen());
    }

    @Test
    void legacyConstructorKeepsLastSeenUnknown() {
        Friend friend = new Friend(UUID.randomUUID(), "Bob", 100L, false);
        assertFalse(friend.online());
        assertEquals(0L, friend.lastSeen());
    }
}
