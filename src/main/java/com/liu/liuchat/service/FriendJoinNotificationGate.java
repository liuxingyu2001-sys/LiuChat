package com.liu.liuchat.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 好友上线提醒去重：同一接收者/同一上线好友在冷却期内只提醒一次。 */
final class FriendJoinNotificationGate {
    private final Map<Key, Long> last = new ConcurrentHashMap<>();

    boolean shouldNotify(String viewerUuid, String targetUuid, long now, long cooldownMillis) {
        if (cooldownMillis <= 0L) return true;
        Key key = new Key(viewerUuid, targetUuid);
        Long previous = last.get(key);
        if (previous != null && now - previous < cooldownMillis) return false;
        last.put(key, now);
        if (last.size() > 4096) {
            last.entrySet().removeIf(entry -> now - entry.getValue() >= cooldownMillis);
        }
        return true;
    }

    void clear() {
        last.clear();
    }

    private record Key(String viewer, String target) { }
}
