package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemotePlayersTest {
    @Test void mergesLocalAndRemotePlayersWithoutDuplicates() {
        RemotePlayers directory = new RemotePlayers();
        directory.update("game", List.of("Alice", "Bob", "bad name"), 1_000L);
        directory.update("lobby", List.of("alice", "Carol"), 1_000L);
        assertEquals(List.of("Alice", "Bob", "Carol"),
                directory.complete("", List.of("Alice"), 1_001L));
        assertEquals(List.of("Bob"), directory.complete("b", List.of(), 1_001L));
        directory.remove("game", "bOB");
        assertEquals(List.of(), directory.complete("b", List.of(), 1_002L));
    }

    @Test void refreshesAndExpiresRemoteEntries() {
        RemotePlayers directory = new RemotePlayers();
        directory.update("game", List.of("Alice", "Bob"), 0L);
        directory.update("game", List.of("Alice"), 60_000L);
        assertEquals(List.of("Alice", "Bob"), directory.complete("", List.of(), 149_999L));
        assertEquals(List.of("Alice"), directory.complete("", List.of(), 150_000L));
        assertEquals(List.of(), directory.complete("", List.of(), 210_000L));
    }
}
