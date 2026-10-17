package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalChatSnapshotTest {

    @Test
    void roundTripsFormatAndSourceBindingToUuid() {
        String uuid = UUID.randomUUID().toString();
        String encoded = ExternalChatSnapshot.encode("&b${sender}: ${message}", "群聊", "group-1", uuid);
        ExternalChatSnapshot.Appearance decoded = ExternalChatSnapshot.decode(encoded, uuid);
        assertNotNull(decoded);
        assertEquals("&b${sender}: ${message}", decoded.format());
        assertEquals("群聊", decoded.source());
        assertEquals("group-1", decoded.target());
        assertNull(ExternalChatSnapshot.decode(encoded, UUID.randomUUID().toString()));
        assertNull(ExternalChatSnapshot.decode("not-ours", uuid));
        assertNull(ExternalChatSnapshot.decode("liuchat-ext-v1:bad", uuid));
    }

    @Test
    void emptyContextAndOversizedFormat() {
        String uuid = UUID.randomUUID().toString();
        ExternalChatSnapshot.Appearance decoded = ExternalChatSnapshot.decode(
                ExternalChatSnapshot.encode("fmt", "", "", uuid), uuid);
        assertNotNull(decoded);
        assertEquals("", decoded.source());
        assertEquals("", decoded.target());
        assertThrows(IllegalArgumentException.class,
                () -> ExternalChatSnapshot.encode("x".repeat(4097), "", "", uuid));
    }

    @Test
    void externalTextInsertsBodyAfterColors() {
        String line = ExternalChatText.line("&8[&9QQ&8] &b${sender}&8» &f${message}",
                "小明", "&c红色", "群聊", "g1");
        assertTrue(line.startsWith("§8[§9QQ§8] §b小明§8» §f"), line);
        assertTrue(line.endsWith("&c红色"), line);
    }
}
