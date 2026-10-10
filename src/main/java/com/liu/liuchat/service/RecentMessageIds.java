package com.liu.liuchat.service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** 按消息 ID 去重；固定期限、容量上限，重复包不延长期限。 */
final class RecentMessageIds {
    private final int capacity;
    private final long ttl;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    RecentMessageIds(int capacity, Duration ttl, LongSupplier clock) {
        if (capacity < 1 || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.ttl = ttl.toNanos();
        this.clock = clock;
    }

    synchronized boolean first(String id) {
        if (id == null || id.isEmpty()) return true; // 旧协议没有标识，不能按内容误判
        long now = clock.getAsLong();
        Iterator<Long> timestamps = seen.values().iterator();
        while (timestamps.hasNext()) {
            if (now - timestamps.next() < ttl) break;
            timestamps.remove();
        }
        if (seen.containsKey(id)) return false;
        if (seen.size() >= capacity) seen.remove(seen.keySet().iterator().next());
        seen.put(id, now);
        return true;
    }

    synchronized void clear() { seen.clear(); }
}
