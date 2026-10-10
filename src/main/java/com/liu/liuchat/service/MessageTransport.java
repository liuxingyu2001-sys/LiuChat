package com.liu.liuchat.service;

import java.util.concurrent.CompletableFuture;

/** 异步传输：true 表示传输已接受消息，不代表目标玩家已收到。 */
@FunctionalInterface
public interface MessageTransport {
    CompletableFuture<Boolean> publish(byte[] payload);
}
