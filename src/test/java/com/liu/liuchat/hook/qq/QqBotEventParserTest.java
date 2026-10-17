package com.liu.liuchat.hook.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QqBotEventParserTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void parsesGroupMessage() {
        QqBotEvent event = QqBotEventParser.parse("GROUP_AT_MESSAGE_CREATE", json("""
                {
                  "id": "evt-1",
                  "content": " 你好 ",
                  "group_openid": "group-1",
                  "author": {"id": "bot-user", "member_openid": "member-1"}
                }
                """));
        assertNotNull(event);
        assertEquals("evt-1", event.eventId());
        assertEquals(QqTarget.Type.GROUP, event.type());
        assertEquals("group-1", event.targetId());
        assertEquals("member-1", event.senderId());
        assertEquals("", event.senderName());
        assertEquals("你好", event.content());
    }

    @Test
    void parsesC2cMessageWithUserOpenid() {
        QqBotEvent event = QqBotEventParser.parse("C2C_MESSAGE_CREATE", json("""
                {
                  "id": "evt-2",
                  "content": "hello",
                  "author": {"id": "fallback", "user_openid": "user-1"}
                }
                """));
        assertNotNull(event);
        assertEquals(QqTarget.Type.C2C, event.type());
        assertEquals("user-1", event.targetId());
        assertEquals("user-1", event.senderId());
    }

    @Test
    void parsesChannelNickname() {
        QqBotEvent event = QqBotEventParser.parse("AT_MESSAGE_CREATE", json("""
                {
                  "id": "evt-3",
                  "content": "<@!1> hi",
                  "channel_id": "channel-1",
                  "author": {"id": "u1", "username": "fallback"},
                  "member": {"nick": "小明"}
                }
                """));
        assertNotNull(event);
        assertEquals(QqTarget.Type.CHANNEL, event.type());
        assertEquals("小明", event.senderName());
        assertEquals("channel-1", event.targetId());
    }

    @Test
    void parsesFullModeGroupMessageAndSkipsBots() {
        QqBotEvent event = QqBotEventParser.parse("GROUP_MESSAGE_CREATE", json("""
                {
                  "id": "evt-4",
                  "content": "普通群消息",
                  "group_openid": "group-1",
                  "author": {"member_openid": "member-1"}
                }
                """));
        assertNotNull(event);
        assertEquals(QqTarget.Type.GROUP, event.type());
        assertEquals("普通群消息", event.content());
        assertNull(QqBotEventParser.parse("GROUP_MESSAGE_CREATE", json("""
                {"id": "evt-5", "content": "bot", "group_openid": "g",
                 "author": {"member_openid": "m", "bot": true}}
                """)));
    }

    @Test
    void parsesDirectMessageAndIgnoresOtherEvents() {
        QqBotEvent event = QqBotEventParser.parse("DIRECT_MESSAGE_CREATE", json("""
                {"id": "d1", "content": "hi", "channel_id": "dm-1",
                 "author": {"id": "u1", "username": "Alice"}}
                """));
        assertNotNull(event);
        assertEquals(QqTarget.Type.DIRECT, event.type());
        assertEquals("Alice", event.senderName());
        assertNull(QqBotEventParser.parse("GUILD_CREATE", json("{}")));
        assertNull(QqBotEventParser.parse("GROUP_AT_MESSAGE_CREATE", json("{}")));
    }
}
