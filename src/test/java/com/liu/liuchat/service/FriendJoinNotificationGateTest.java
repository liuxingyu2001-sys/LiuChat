package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendJoinNotificationGateTest {

    @Test
    void suppressesRepeatWithinCooldownAndAllowsAfter() {
        FriendJoinNotificationGate gate = new FriendJoinNotificationGate();
        assertTrue(gate.shouldNotify("viewer", "target", 1_000L, 5_000L));
        assertFalse(gate.shouldNotify("viewer", "target", 2_000L, 5_000L));
        assertTrue(gate.shouldNotify("viewer", "target", 6_001L, 5_000L));
    }

    @Test
    void keepsDifferentViewersAndTargetsIndependent() {
        FriendJoinNotificationGate gate = new FriendJoinNotificationGate();
        assertTrue(gate.shouldNotify("viewer-a", "target", 1_000L, 5_000L));
        assertTrue(gate.shouldNotify("viewer-b", "target", 2_000L, 5_000L));
        assertFalse(gate.shouldNotify("viewer-a", "target", 3_000L, 5_000L));
        assertTrue(gate.shouldNotify("viewer-a", "other", 3_000L, 5_000L));
    }

    @Test
    void zeroCooldownAlwaysNotifies() {
        FriendJoinNotificationGate gate = new FriendJoinNotificationGate();
        assertTrue(gate.shouldNotify("viewer", "target", 1_000L, 0L));
        assertTrue(gate.shouldNotify("viewer", "target", 1_000L, 0L));
    }
}
