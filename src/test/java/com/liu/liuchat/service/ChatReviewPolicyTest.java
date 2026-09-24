package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatReviewPolicyTest {
    private static final List<String> KEYWORDS = List.of("广告", "加群", "cnm", "私服");

    @Test void blocksEveryConfiguredKeyword() {
        for (String text : List.of("来看广告", "加-群", "c.n.m", "c你n好m", "某私服"))
            assertTrue(blocked(text));
        assertTrue(ChatReviewPolicy.blocked("广-告", List.of("广*告"), false, false, false));
        assertTrue(ChatReviewPolicy.blocked("代A充", List.of("代?充"), false, false, false));
    }

    @Test void blocksIpDomainsAndPorts() {
        for (String text : List.of("127.0.0.1", "203.0.113.45:25565", "http://10.0.0.1:80",
                "cko.cc:25565", "2a2t.org:25565", "mxxz.game", "https://example.com/news"))
            assertTrue(blocked(text));
        assertFalse(blocked("版本 1.21.11"));
        assertFalse(blocked("不是 IP 256.1.2.3"));
        assertFalse(ChatReviewPolicy.blocked("127.0.0.1", List.of(), false, false, false));
    }

    @Test void blocksContactsWithoutAi() {
        for (String text : List.of("联系 13800138000", "我的QQ 12345678")) assertTrue(blocked(text));
        assertFalse(ChatReviewPolicy.blocked("QQ 12345678", List.of(), false, false, false));
        assertFalse(blocked("你好，介绍服务器玩法"));
        assertFalse(blocked("c天气很不错n今天m"));
        assertFalse(ChatReviewPolicy.blocked("普通消息", List.of("*"), false, false, false));
        assertTrue(blocked("x".repeat(1001)));
    }

    private static boolean blocked(String text) {
        return ChatReviewPolicy.blocked(text, KEYWORDS, true, true, true);
    }
}
