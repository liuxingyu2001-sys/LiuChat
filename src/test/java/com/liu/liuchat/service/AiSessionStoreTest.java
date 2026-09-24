package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSessionStoreTest {
    private static final Logger LOG = Logger.getLogger("AiSessionStoreTest");

    @TempDir
    Path dir;

    private AiSessionStore store() {
        return new AiSessionStore(dir.resolve("ai-sessions.json"), LOG);
    }

    private static AiSessionStore.Limits limits(int messages, int chars, int ttl) {
        return new AiSessionStore.Limits(messages, chars, ttl);
    }

    private static void round(AiSessionStore store, String assistant, String question, String answer,
                              AiSessionStore.Limits limits) {
        store.finish(store.open(assistant, limits, question), answer, limits);
    }

    private static List<String> questions(List<AiClient.Msg> history) {
        return history.stream().filter(msg -> msg.role().equals("user")).map(AiClient.Msg::content).toList();
    }

    @Test void capsSharedHistoryAtMessageLimit() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(4, 0, 0); // 4 条消息 = 2 轮
        for (int i = 1; i <= 5; i++) round(store, "guide", "q" + i, "a" + i, limits);

        List<AiClient.Msg> history = store.context("guide", limits);
        assertEquals(4, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("q4", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("a4", history.get(1).content());
        assertEquals("q5", history.get(2).content());
        assertEquals("a5", history.get(3).content());
        // 每个助手一份，互不串台
        assertEquals(List.of(), store.context("bot", limits));
    }

    @Test void keepsSeparateSessionPerAssistant() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(20, 0, 0);
        round(store, "guide", "怎么领取礼包", "输入 /kit", limits);
        round(store, "bot", "服务器多久重启", "每天 4 点", limits);

        assertEquals(List.of("怎么领取礼包"), questions(store.context("guide", limits)));
        assertEquals(List.of("服务器多久重启"), questions(store.context("bot", limits)));
    }

    @Test void pendingTurnIsInvisibleUntilAnsweredAndDroppedOnFailure() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(20, 0, 0);
        AiSessionStore.Turn pending = store.open("bot", limits, "还没回");
        assertEquals(List.of(), store.context("bot", limits));
        store.fail(pending);
        assertEquals(List.of(), store.context("bot", limits));

        round(store, "bot", "正常", "回答", limits);
        assertEquals(List.of("正常"), questions(store.context("bot", limits)));
    }

    @Test void trimsByCharBudgetFromOldest() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(100, 15, 0); // 每轮 6 字符，预算 15 = 最多留 2 轮
        for (int i = 1; i <= 5; i++) round(store, "bot", "问题" + i, "回答" + i, limits);

        List<String> questions = questions(store.context("bot", limits));
        assertEquals(List.of("问题4", "问题5"), questions);
    }

    @Test void idleSessionExpiresWhenTtlSet() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(20, 0, 60);
        round(store, "bot", "旧问题", "旧回答", limits);
        long now = System.currentTimeMillis();
        assertEquals(2, store.context("bot", limits, now).size()); // 问 + 答各算 1 条
        assertEquals(List.of(), store.context("bot", limits, now + 61_000L));
        // 过期后会话被清空，再问是全新上下文
        assertEquals(List.of(), store.context("bot", limits, now + 62_000L));
    }

    @Test void zeroTtlNeverExpires() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(20, 0, 0);
        round(store, "bot", "q", "a", limits);
        assertEquals(2, store.context("bot", limits, System.currentTimeMillis() + 10_000_000L).size());
    }

    @Test void historyDisabledDropsEverything() {
        AiSessionStore store = store();
        AiSessionStore.Limits on = limits(20, 0, 0);
        round(store, "bot", "q", "a", on);
        AiSessionStore.Limits off = limits(0, 0, 0);
        assertEquals(List.of(), store.context("bot", off));
        store.finish(store.open("bot", off, "q2"), "a2", off);
        assertEquals(List.of(), store.context("bot", on)); // 关闭期间产生的轮次不留
    }

    @Test void survivesRestart() throws Exception {
        AiSessionStore.Limits limits = limits(20, 0, 0);
        AiSessionStore first = store();
        round(first, "guide", "第一条", "第一条回答", limits);
        first.close();
        assertTrue(Files.exists(dir.resolve("ai-sessions.json")));

        AiSessionStore second = store();
        second.load();
        assertEquals(List.of("第一条"), questions(second.context("guide", limits)));
    }

    @Test void persistDisabledNeverTouchesDisk() throws Exception {
        AiSessionStore.Limits limits = limits(20, 0, 0);
        AiSessionStore first = store();
        first.setPersist(false);
        round(first, "bot", "q", "a", limits);
        first.close();
        assertFalse(Files.exists(dir.resolve("ai-sessions.json")));

        AiSessionStore second = store();
        second.setPersist(false);
        second.load();
        assertEquals(List.of(), second.context("bot", limits));
    }

    @Test void corruptFileIsIgnored() throws Exception {
        Files.writeString(dir.resolve("ai-sessions.json"), "{ 这不是 json");
        AiSessionStore store = store();
        store.load();
        assertEquals(List.of(), store.context("bot", limits(20, 0, 0)));
    }
}
