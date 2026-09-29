package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecentTellContactsTest {
    @Test
    void localDeliveryUpdatesBothSidesAndLaterMessagesReplaceContacts() {
        RecentTellContacts contacts = new RecentTellContacts();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        assertNull(contacts.get(a));
        contacts.delivered(a, "Alice", b, "Bob");
        assertEquals("Bob", contacts.get(a));
        assertEquals("Alice", contacts.get(b));
        contacts.delivered(c, "Carol", b, "Bob");
        assertEquals("Carol", contacts.get(b));
        assertEquals("Bob", contacts.get(c));
    }

    @Test
    void remoteReceptionAndConfirmationOnlyUpdateTheirOwnSide() {
        RecentTellContacts contacts = new RecentTellContacts();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        contacts.received(recipient, "Alice");
        assertEquals("Alice", contacts.get(recipient));
        assertNull(contacts.get(sender));
        contacts.confirmed(sender, "Bob");
        assertEquals("Bob", contacts.get(sender));
    }
}
