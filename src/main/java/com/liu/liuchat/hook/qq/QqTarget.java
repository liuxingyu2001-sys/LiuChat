package com.liu.liuchat.hook.qq;

import java.util.Locale;

/**
 * QQ 官方机器人的一个会话目标。
 *
 * <p>{@link Type#GROUP} 对应群聊 openid，{@link Type#C2C} 对应用户 openid；
 * {@link Type#CHANNEL} 与 {@link Type#DIRECT} 都通过频道消息接口发送，但接收事件不同。
 */
public record QqTarget(Type type, String id) {

    public enum Type {
        GROUP("群聊", "group"),
        C2C("私聊", "c2c"),
        CHANNEL("频道", "channel"),
        DIRECT("私信", "direct");

        private final String label;
        private final String key;

        Type(String label, String key) {
            this.label = label;
            this.key = key;
        }

        public String label() {
            return label;
        }

        public String key() {
            return key;
        }

        /** 群聊/私聊走 v2 消息接口，频道/私信走 /channels 接口。 */
        public boolean isChannel() {
            return this == CHANNEL || this == DIRECT;
        }
    }

    public QqTarget {
        if (type == null) throw new IllegalArgumentException("QQ target type is required");
        id = id == null ? "" : id.strip();
        if (id.isEmpty()) throw new IllegalArgumentException("QQ target id is required");
    }

    /**
     * 解析 {@code group:xxx}、{@code c2c:xxx}、{@code channel:xxx}、{@code direct:xxx}；
     * 不带前缀时使用 {@code fallback}。
     *
     * @return 无法解析（空串、未知前缀、缺少 ID）时返回 null
     */
    public static QqTarget parse(String raw, Type fallback) {
        if (raw == null) return null;
        String text = raw.strip();
        if (text.isEmpty()) return null;
        int colon = text.indexOf(':');
        if (colon > 0) {
            Type type = type(text.substring(0, colon));
            if (type == null) return null;
            String id = text.substring(colon + 1).strip();
            return id.isEmpty() ? null : new QqTarget(type, id);
        }
        return fallback == null ? null : new QqTarget(fallback, text);
    }

    /** 中文/英文前缀都接受，方便配置书写。 */
    public static Type type(String raw) {
        if (raw == null) return null;
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "group", "群", "群聊" -> Type.GROUP;
            case "c2c", "user", "私聊", "用户" -> Type.C2C;
            case "channel", "guild", "频道" -> Type.CHANNEL;
            case "direct", "私信" -> Type.DIRECT;
            default -> null;
        };
    }

    /** 配置/日志用的稳定键：{@code group:xxx}。 */
    public String key() {
        return type.key() + ":" + id;
    }

    public String label() {
        return type.label();
    }
}
