package com.liu.liuchat.service;

import java.util.concurrent.CompletableFuture;

/** 优先传输失败后只尝试一次后备传输；成功结果不触发后备。 */
final class FallbackTransport implements MessageTransport {
    private final MessageTransport primary;
    private final MessageTransport fallback;

    FallbackTransport(MessageTransport primary, MessageTransport fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<Boolean> publish(byte[] payload) {
        CompletableFuture<Boolean> first;
        try {
            first = primary.publish(payload);
        } catch (RuntimeException error) {
            first = CompletableFuture.failedFuture(error);
        }
        return first.handle((sent, error) -> error == null && Boolean.TRUE.equals(sent))
                .thenCompose(sent -> sent ? CompletableFuture.completedFuture(true) : fallback.publish(payload));
    }
}
