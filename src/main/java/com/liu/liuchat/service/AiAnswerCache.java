package com.liu.liuchat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 相同配置 + 相同上下文 + 相同问题的答案缓存，命中直接返回、不发请求（0 token）。
 * <p>
 * 上下文指纹参与 key：开着多轮上下文时会话一变缓存自动失效，不会拿旧答案答非所问；
 * 上下文关闭时指纹恒定，重复提问 100% 命中。
 */
public final class AiAnswerCache {
    private static final class Cached {
        private final String answer;
        private final long expiresAt;

        private Cached(String answer, long expiresAt) {
            this.answer = answer;
            this.expiresAt = expiresAt;
        }
    }

    private final int maxEntries;
    private final Map<String, Cached> entries;

    public AiAnswerCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = new LinkedHashMap<String, Cached>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > AiAnswerCache.this.maxEntries;
            }
        };
    }

    /** 命中返回原答案，过期或不存在返回 null。 */
    public synchronized String get(String key, long now) {
        Cached entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt <= now) {
            entries.remove(key);
            return null;
        }
        return entry.answer;
    }

    public synchronized void put(String key, String answer, long ttlMillis, long now) {
        if (key == null || answer == null || answer.isBlank() || ttlMillis <= 0) return;
        entries.put(key, new Cached(answer, now + ttlMillis));
    }

    public synchronized void clear() { entries.clear(); }

    public synchronized int size() { return entries.size(); }

    /** 会话上下文指纹：内容完全相同才可能命中缓存。 */
    public static String fingerprint(List<AiClient.Msg> history) {
        if (history == null || history.isEmpty()) return "0";
        StringBuilder text = new StringBuilder();
        for (AiClient.Msg msg : history) {
            if (msg == null) continue;
            text.append(msg.role()).append('\u0001').append(msg.content()).append('\u0002');
        }
        return sha256(text.toString());
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
