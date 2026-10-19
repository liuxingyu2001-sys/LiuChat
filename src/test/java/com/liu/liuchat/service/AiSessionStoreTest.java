package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test void longPerPlayerKeysStayIsolatedInsteadOfColliding() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(20, 0, 0);
        // 助手名 47 字符：键 助手#uuid 会超过 48 字符上限，旧实现按长度截断后两个玩家会串台
        String assistant = "s".repeat(47);
        String first = AiAssistantService.sessionKey(assistant, UUID.fromString("11111111-1111-1111-1111-111111111111"), false);
        String second = AiAssistantService.sessionKey(assistant, UUID.fromString("22222222-2222-2222-2222-222222222222"), false);
        assertNotEquals(first, second);

        round(store, first, "玩家1的问题", "玩家1的回答", limits);
        round(store, second, "玩家2的问题", "玩家2的回答", limits);
        assertEquals(List.of("玩家1的问题"), questions(store.context(first, limits)));
        assertEquals(List.of("玩家2的问题"), questions(store.context(second, limits)));
        // 共享模式的助手名会话不受影响
        assertEquals(List.of(), questions(store.context(assistant, limits)));

        // 落盘再读：超长键在存储层归一化（前缀 + 哈希）后仍然互不串台
        store.close();
        AiSessionStore reloaded = store();
        reloaded.load();
        assertEquals(List.of("玩家1的问题"), questions(reloaded.context(first, limits)));
        assertEquals(List.of("玩家2的问题"), questions(reloaded.context(second, limits)));
    }

    @Test void keepsMoreThanSixtyFourSessionsForPerPlayerMemory() {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(4, 0, 0);
        for (int i = 0; i < 70; i++) round(store, "s-" + i, "q" + i, "a" + i, limits);
        assertEquals(List.of("q0"), questions(store.context("s-0", limits)), "按玩家记忆下会话更多，护栏应放行");
        assertEquals(List.of("q69"), questions(store.context("s-69", limits)));
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

    @Test void saveKeepsFileUnderLoadLimitByDroppingOldestSessions() throws Exception {
        AiSessionStore store = store();
        AiSessionStore.Limits limits = limits(20, 0, 0);
        String question = "问".repeat(2000);
        String answer = "答".repeat(4000);
        // 256 个会话 × 6000 个中文字符 ≈ 4.6 MB，超过 load() 读取的上限；写盘时必须先丢弃最旧会话
        for (int i = 0; i < 256; i++) store.finish(store.open("s-" + i, limits, question), answer, limits);
        store.close();

        assertTrue(Files.size(dir.resolve("ai-sessions.json")) <= 4L * 1024 * 1024,
                "写出的文件不能超过 load() 的读取上限，否则重启会整份丢弃");
        AiSessionStore reloaded = store();
        reloaded.load();
        boolean anySurvived = false;
        for (int i = 0; i < 256 && !anySurvived; i++)
            anySurvived = !reloaded.context("s-" + i, limits).isEmpty();
        assertTrue(anySurvived, "丢弃最旧会话后，其余会话仍要能读回");
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
