package com.liu.liuchat.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 外部聊天（QQ 机器人等）的固定格式快照，放在跨服 CHAT 的 placeholders 字段里。
 *
 * <p>与 AI 快照类似：发送服决定格式，接收服原样渲染，避免各子服聊天格式不同导致
 * QQ 消息在每台服长得不一样。UUID 参与校验，普通玩家不能靠占位符伪造。
 */
public final class ExternalChatSnapshot {
    private static final String PREFIX = "liuchat-ext-v1:";
    private static final int MAX_FORMAT_BYTES = 4096;
    private static final int MAX_CONTEXT_BYTES = 256;

    public record Appearance(String format, String source, String target) { }

    private ExternalChatSnapshot() { }

    public static String encode(String format, String source, String target, String uuid) {
        byte[] formatBytes = (format == null ? "" : format).getBytes(StandardCharsets.UTF_8);
        byte[] sourceBytes = (source == null ? "" : source).getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = (target == null ? "" : target).getBytes(StandardCharsets.UTF_8);
        if (formatBytes.length > MAX_FORMAT_BYTES || sourceBytes.length > MAX_CONTEXT_BYTES
                || targetBytes.length > MAX_CONTEXT_BYTES) {
            throw new IllegalArgumentException("External chat snapshot exceeds limit");
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + encoder.encodeToString(formatBytes) + ":" + encoder.encodeToString(sourceBytes)
                + ":" + encoder.encodeToString(targetBytes) + ":" + uuid;
    }

    /** @return 不是外部聊天快照 / UUID 不匹配 / 数据损坏时返回 null */
    public static Appearance decode(String data, String uuid) {
        if (data == null || uuid == null || !data.startsWith(PREFIX)) return null;
        int first = data.indexOf(':', PREFIX.length());
        int second = first < 0 ? -1 : data.indexOf(':', first + 1);
        int third = second < 0 ? -1 : data.indexOf(':', second + 1);
        if (third < 0) return null;
        try {
            String expected = data.substring(third + 1);
            if (!uuid.equals(expected)) return null;
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] formatBytes = decoder.decode(data.substring(PREFIX.length(), first));
            byte[] sourceBytes = decoder.decode(data.substring(first + 1, second));
            byte[] targetBytes = decoder.decode(data.substring(second + 1, third));
            if (formatBytes.length > MAX_FORMAT_BYTES || sourceBytes.length > MAX_CONTEXT_BYTES
                    || targetBytes.length > MAX_CONTEXT_BYTES) return null;
            return new Appearance(new String(formatBytes, StandardCharsets.UTF_8),
                    new String(sourceBytes, StandardCharsets.UTF_8),
                    new String(targetBytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
