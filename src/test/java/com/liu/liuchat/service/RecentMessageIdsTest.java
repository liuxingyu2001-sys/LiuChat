package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecentMessageIdsTest {
    @Test void distinctIdsAllowIdenticalContentAndDuplicateDoesNotExtendExpiry() {
        AtomicLong now = new AtomicLong();
        var ids = new RecentMessageIds(10, Duration.ofNanos(100), now::get);
        assertTrue(ids.first("a"));
        assertTrue(ids.first("b"));
        now.set(99);
        assertFalse(ids.first("a"));
        now.set(100);
        assertTrue(ids.first("a"));
    }

    @Test void capacityEvictsOldestAndClearAllowsNewDelivery() {
        var ids = new RecentMessageIds(2, Duration.ofMinutes(2), () -> 0L);
        assertTrue(ids.first("a"));
        assertTrue(ids.first("b"));
        assertTrue(ids.first("c"));
        assertFalse(ids.first("b"));
        assertTrue(ids.first("a"));
        ids.clear();
        assertTrue(ids.first("a"));
    }

    @Test void legacyMessagesAreNotDeduplicatedByContent() {
        var ids = new RecentMessageIds(2, Duration.ofMinutes(2), () -> 0L);
        assertTrue(ids.first(""));
        assertTrue(ids.first(""));
    }
}
