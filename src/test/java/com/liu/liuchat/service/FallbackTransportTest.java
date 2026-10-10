package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FallbackTransportTest {
    @Test void successfulPrimaryNeverFallsBack() {
        AtomicInteger calls = new AtomicInteger();
        var transport = new FallbackTransport(payload -> CompletableFuture.completedFuture(true), payload -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });
        assertTrue(transport.publish(new byte[0]).join());
        assertEquals(0, calls.get());
    }

    @Test void waitsForPrimaryAndFallsBackOnceOnFailure() {
        var first = new CompletableFuture<Boolean>();
        AtomicInteger calls = new AtomicInteger();
        var transport = new FallbackTransport(payload -> first, payload -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(false);
        });
        var result = transport.publish(new byte[0]);
        assertFalse(result.isDone());
        assertEquals(0, calls.get());
        first.complete(false);
        assertFalse(result.join());
        assertEquals(1, calls.get());
    }

    @Test void thrownAndAsynchronousErrorsBothFallBack() {
        MessageTransport fallback = payload -> CompletableFuture.completedFuture(true);
        assertTrue(new FallbackTransport(payload -> { throw new IllegalStateException(); }, fallback)
                .publish(new byte[0]).join());
        assertTrue(new FallbackTransport(payload -> CompletableFuture.failedFuture(new IllegalStateException()), fallback)
                .publish(new byte[0]).join());
    }
}
