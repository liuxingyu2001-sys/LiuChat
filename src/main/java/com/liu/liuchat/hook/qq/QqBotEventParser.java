package com.liu.liuchat.hook.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 把 QQ 网关事件 JSON 转成 {@link QqBotEvent}。只处理消息事件，其余事件返回 null。
 *
 * <p>官方事件里的昵称并不稳定：频道事件通常有 {@code author.username}/{@code member.nick}，
 * 群聊/私聊一般只给 openid。这里没有昵称时返回空字符串，由桥接层生成短名。
 */
public final class QqBotEventParser {

    private QqBotEventParser() {
    }

    public static QqBotEvent parse(String eventType, JsonObject data) {
        if (eventType == null || data == null) return null;
        return switch (eventType) {
            case "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> group(data);
            case "C2C_MESSAGE_CREATE" -> c2c(data);
            case "AT_MESSAGE_CREATE" -> channel(data, QqTarget.Type.CHANNEL);
            case "DIRECT_MESSAGE_CREATE" -> channel(data, QqTarget.Type.DIRECT);
            default -> null;
        };
    }

    private static QqBotEvent group(JsonObject data) {
        String group = string(data, "group_openid");
        if (group.isEmpty()) return null;
        JsonObject author = object(data, "author");
        if (isBot(author)) return null;
        String senderId = first(author, "member_openid", "id", "union_openid");
        String senderName = first(author, "nickname", "username", "global_name");
        return event(data, QqTarget.Type.GROUP, group, senderId, senderName);
    }

    private static QqBotEvent c2c(JsonObject data) {
        JsonObject author = object(data, "author");
        if (isBot(author)) return null;
        String user = first(author, "user_openid", "id");
        if (user.isEmpty()) user = string(data, "user_openid");
        if (user.isEmpty()) return null;
        String senderName = first(author, "nickname", "username", "global_name");
        return event(data, QqTarget.Type.C2C, user, user, senderName);
    }

    private static QqBotEvent channel(JsonObject data, QqTarget.Type type) {
        String channel = string(data, "channel_id");
        if (channel.isEmpty()) return null;
        JsonObject author = object(data, "author");
        if (isBot(author)) return null;
        JsonObject member = object(data, "member");
        String senderId = first(author, "id", "user_openid");
        String senderName = first(member, "nick", "username");
        if (senderName.isEmpty()) senderName = first(author, "global_name", "username", "nickname");
        return event(data, type, channel, senderId, senderName);
    }

    private static boolean isBot(JsonObject author) {
        JsonElement value = author == null ? null : author.get("bot");
        if (value == null || !value.isJsonPrimitive()) return false;
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static QqBotEvent event(JsonObject data, QqTarget.Type type, String targetId,
                                    String senderId, String senderName) {
        String content = string(data, "content");
        return new QqBotEvent(string(data, "id"), type, targetId, senderId, senderName, content);
    }

    private static JsonObject object(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String first(JsonObject source, String... keys) {
        if (source == null) return "";
        for (String key : keys) {
            String value = string(source, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String string(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString().strip();
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
