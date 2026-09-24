package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiAnswerCacheTest {

    @Test void hitsWithinTtlAndExpiresAfterwards() {
        AiAnswerCache cache = new AiAnswerCache(8);
        cache.put("k", "答案", 1000L, 10_000L);
        assertEquals("答案", cache.get("k", 10_999L));
        assertNull(cache.get("k", 11_000L));
        assertNull(cache.get("k", 12_000L));
    }

    @Test void ignoresBlankAnswersAndZeroTtl() {
        AiAnswerCache cache = new AiAnswerCache(8);
        cache.put("a", "  ", 1000L, 0L);
        cache.put("b", "答案", 0L, 0L);
        assertNull(cache.get("a", 1L));
        assertNull(cache.get("b", 1L));
        assertEquals(0, cache.size());
    }

    @Test void evictsLeastRecentlyUsedBeyondCapacity() {
        AiAnswerCache cache = new AiAnswerCache(2);
        cache.put("1", "a", 60_000L, 0L);
        cache.put("2", "b", 60_000L, 0L);
        cache.get("1", 1L); // 1 变成最近使用，淘汰 2
        cache.put("3", "c", 60_000L, 2L);
        assertEquals("a", cache.get("1", 3L));
        assertNull(cache.get("2", 3L));
        assertEquals("c", cache.get("3", 3L));
        assertEquals(2, cache.size());
    }

    @Test void fingerprintTracksHistoryContentAndOrder() {
        AiClient.Msg user = new AiClient.Msg("user", "你好");
        AiClient.Msg answer = new AiClient.Msg("assistant", "你好呀");
        assertEquals(AiAnswerCache.fingerprint(List.of(user, answer)),
                AiAnswerCache.fingerprint(List.of(user, answer)));
        assertEquals("0", AiAnswerCache.fingerprint(List.of()));
        assertEquals("0", AiAnswerCache.fingerprint(null));
        assertNotEquals(AiAnswerCache.fingerprint(List.of(user, answer)),
                AiAnswerCache.fingerprint(List.of(answer, user)));
        assertNotEquals(AiAnswerCache.fingerprint(List.of(user)),
                AiAnswerCache.fingerprint(List.of(new AiClient.Msg("user", "你好啊"))));
    }
}
