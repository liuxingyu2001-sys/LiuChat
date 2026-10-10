package com.liu.liuchat.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** 请求生命周期：先注册再发送；确认、失败、超时只能有一个结果。 */
final class PendingRequests<T> {
    private final ConcurrentHashMap<String, CompletableFuture<T>> requests = new ConcurrentHashMap<>();
    private final Function<Runnable, Runnable> scheduleTimeout;

    /** 调度函数返回取消定时任务的动作。 */
    PendingRequests(Function<Runnable, Runnable> scheduleTimeout) {
        this.scheduleTimeout = scheduleTimeout;
    }

    CompletableFuture<T> register(String id) {
        var future = new CompletableFuture<T>();
        if (requests.putIfAbsent(id, future) != null) throw new IllegalArgumentException("Duplicate request: " + id);
        try {
            Runnable cancel = scheduleTimeout.apply(() -> fail(id, new TimeoutException("Request timed out: " + id)));
            future.whenComplete((value, error) -> {
                requests.remove(id, future);
                cancel.run();
            });
        } catch (RuntimeException error) {
            requests.remove(id, future);
            future.completeExceptionally(error);
        }
        return future;
    }

    void complete(String id, T value) {
        var future = requests.remove(id);
        if (future != null) future.complete(value);
    }

    void fail(String id, Throwable error) {
        var future = requests.remove(id);
        if (future != null) future.completeExceptionally(error);
    }

    void clear() {
        requests.forEach((id, future) -> {
            if (requests.remove(id, future)) future.cancel(false);
        });
    }
}
