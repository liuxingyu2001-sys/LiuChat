package com.liu.liuchat.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Source-server presentation for an AI chat reply, carried in CHAT's existing placeholders field. */
final class AiChatSnapshot {
    private static final String PREFIX = "liuchat-ai-v1:";
    private static final int MAX_FORMAT_BYTES = 4096;

    record Appearance(String format, UUID headUuid) { }

    private AiChatSnapshot() { }

    static String encode(String format, UUID headUuid) {
        byte[] bytes = format.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FORMAT_BYTES) throw new IllegalArgumentException("AI chat format exceeds limit");
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + ":" + headUuid;
    }

    static Appearance decode(String data, String name, String uuid) {
        if (data == null || !data.startsWith(PREFIX) || !PublicChatAiService.aiUuid(name).toString().equals(uuid))
            return null;
        int separator = data.indexOf(':', PREFIX.length());
        if (separator < 0) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(data.substring(PREFIX.length(), separator));
            if (bytes.length > MAX_FORMAT_BYTES) return null;
            UUID headUuid = UUID.fromString(data.substring(separator + 1));
            return new Appearance(new String(bytes, StandardCharsets.UTF_8), headUuid);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
