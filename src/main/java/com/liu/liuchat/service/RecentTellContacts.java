package com.liu.liuchat.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Recent private-message contacts for this plugin session. */
final class RecentTellContacts {
    private final Map<UUID, String> contacts = new ConcurrentHashMap<>();

    void delivered(UUID sender, String senderName, UUID recipient, String recipientName) {
        contacts.put(sender, recipientName);
        contacts.put(recipient, senderName);
    }

    void received(UUID recipient, String senderName) {
        contacts.put(recipient, senderName);
    }

    void confirmed(UUID sender, String recipientName) {
        contacts.put(sender, recipientName);
    }

    String get(UUID player) {
        return contacts.get(player);
    }
}
