package com.liu.liuchat.service;

import com.liu.liuchat.api.FriendResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 结果码到消息键的映射必须覆盖每次新增的结果，且成功时回落到调用方给的成功键。 */
class FriendResultMessagesTest {

    @Test
    void successfulResultUsesCallerKey() {
        assertEquals("friend.ok", FriendResultMessages.key(FriendResult.OK, "friend.ok"));
        assertEquals("mail.send-ok", FriendResultMessages.key(FriendResult.OK, "mail.send-ok"));
    }

    @Test
    void teleportResultsMapToFriendlyTeleportMessages() {
        assertNotEquals("friend.storage-unavailable",
                FriendResultMessages.key(FriendResult.TELEPORT_CROSS_SERVER, "friend.teleport-ok"));
        assertEquals("friend.teleport-queued",
                FriendResultMessages.key(FriendResult.TELEPORT_CROSS_SERVER, "friend.teleport-ok"));
        assertEquals("friend.teleport-other-server",
                FriendResultMessages.key(FriendResult.TELEPORT_OTHER_SERVER, "friend.teleport-ok"));
        assertEquals("friend.teleport-no-permission",
                FriendResultMessages.key(FriendResult.TELEPORT_DENIED, "friend.teleport-ok"));
    }
}
