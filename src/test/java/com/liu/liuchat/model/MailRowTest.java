package com.liu.liuchat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailRowTest {

    private static MailRow mail(boolean read, boolean claimed) {
        return new MailRow("id", "sender", "Sender", "recipient", "Recipient",
                "subject", "body", List.of("a", "b"), 100, 200, read, claimed);
    }

    @Test
    void expiryUsesZeroAsNever() {
        assertFalse(mail(false, false).expired(199));
        assertTrue(mail(false, false).expired(200));
        MailRow never = new MailRow("id", "s", "S", "r", "R", "x", "y", List.of(), 1, 0, false, false);
        assertFalse(never.expired(Long.MAX_VALUE));
    }

    @Test
    void transitionsKeepOtherFields() {
        MailRow read = mail(false, false).asRead();
        assertTrue(read.read());
        assertFalse(read.claimed());
        MailRow claimed = read.asClaimed();
        assertTrue(claimed.read());
        assertTrue(claimed.claimed());
        assertEquals(2, claimed.attachments().size());
    }

    @Test
    void requestExpiryWorks() {
        assertFalse(new FriendRequestRow("s", "S", "t", "T", 1, 100).expired(99));
        assertTrue(new FriendRequestRow("s", "S", "t", "T", 1, 100).expired(100));
        assertFalse(new FriendRequestRow("s", "S", "t", "T", 1, 0).expired(Long.MAX_VALUE));
    }
}
