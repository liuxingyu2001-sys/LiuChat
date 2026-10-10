package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PendingRequestsTest {
    @Test void acknowledgementCancelsTimeoutAndLateTimeoutDoesNothing() {
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        AtomicInteger cancelled = new AtomicInteger();
        var pending = new PendingRequests<Boolean>(action -> {
            timeout.set(action);
            return cancelled::incrementAndGet;
        });
        var future = pending.register("id");
        pending.complete("id", true);
        timeout.get().run();
        assertTrue(future.join());
        assertEquals(1, cancelled.get());
    }

    @Test void timeoutWinsOverLateAcknowledgement() {
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        var pending = new PendingRequests<Boolean>(action -> { timeout.set(action); return () -> {}; });
        var future = pending.register("id");
        timeout.get().run();
        pending.complete("id", true);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test void failureAndShutdownCancelTimers() {
        AtomicInteger cancelled = new AtomicInteger();
        var pending = new PendingRequests<Boolean>(action -> cancelled::incrementAndGet);
        var failed = pending.register("first");
        var stopped = pending.register("second");
        pending.fail("first", new IllegalStateException());
        pending.clear();
        assertTrue(failed.isCompletedExceptionally());
        assertTrue(stopped.isCancelled());
        assertEquals(2, cancelled.get());
    }
}
