package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatTriggersTest {

    @Test void triggersOnAtAndBareName() {
        assertTrue(AiChatTriggers.triggers("@小派蒙 在吗", "小派蒙"));
        assertTrue(AiChatTriggers.triggers("小派蒙 早上好", "小派蒙"));
        assertTrue(AiChatTriggers.triggers("小派蒙,你好!", "小派蒙"));
        assertTrue(AiChatTriggers.triggers("有人见过小派蒙吗", "小派蒙"));
    }

    @Test void asciiNameMatchesIgnoreCaseWithWordBoundaries() {
        assertTrue(AiChatTriggers.triggers("hey steve what's up", "Steve"));
        assertTrue(AiChatTriggers.triggers("@STEVE hi", "Steve"));
        assertFalse(AiChatTriggers.triggers("MySteve2 is here", "Steve"));
        assertFalse(AiChatTriggers.triggers("steve2 fell", "Steve"));
        assertFalse(AiChatTriggers.triggers("presteve poststeve", "Steve"));
    }

    @Test void coloredRemoteMentionUsesVisibleText() {
        String gradient = "§x§a§f§d§9§c§2b§x§a§e§d§9§c§2o§x§a§d§d§a§c§3t 你好";
        assertTrue(AiChatTriggers.triggers(AiChatTriggers.visibleText(gradient), "bot"));
        assertTrue(AiChatTriggers.triggers(AiChatTriggers.visibleText("§b@§lbot 在吗"), "bot"));
        assertTrue(AiChatTriggers.triggers(AiChatTriggers.visibleText("§7bot§r 你好"), "bot"));
        assertFalse(AiChatTriggers.triggers(AiChatTriggers.visibleText("§brobot"), "bot"));
        assertTrue(AiChatTriggers.visibleText(gradient).contains("bot 你好"));
        assertTrue(AiChatTriggers.visibleText(gradient).equals(AiChatTriggers.visibleText("§b" + gradient)));
    }

    @Test void visibleTextPreservesLiteralAmpersandsAndHandlesNull() {
        assertTrue(AiChatTriggers.visibleText("§bA&b §x§F§F§0§0§0§0B").equals("A&b B"));
        assertTrue(AiChatTriggers.visibleText(null) == null);
    }

    @Test void keywordsMatchLiteralVisibleText() {
        assertTrue(AiChatTriggers.matchesKeyword(AiChatTriggers.visibleText("§x§f§f§0§0§0§0求§a助一下"),
                List.of("求助")));
        assertTrue(AiChatTriggers.matchesKeyword("SERVER 活动", List.of("server")));
        assertTrue(AiChatTriggers.matchesKeyword("a.b", List.of("a.b")));
        assertFalse(AiChatTriggers.matchesKeyword("acb", List.of("a.b")));
        assertFalse(AiChatTriggers.matchesKeyword("闲聊", List.of("", " ")));
        assertFalse(AiChatTriggers.matchesKeyword("闲聊", null));
    }

    @Test void proactiveQuestionRetainsRecentTopicWithinBudget() {
        String question = AiChatTriggers.composeProactiveQuestion(
                List.of("Alice: 很早的消息".repeat(20), "Bob: 今天挖到钻石了"), "小派蒙", "聊聊钻石", 120);
        assertTrue(question.length() <= 120);
        assertTrue(question.contains("Bob: 今天挖到钻石了"));
        assertFalse(question.contains("很早的消息"));
        assertTrue(question.contains("聊聊钻石"));
        assertTrue(AiChatTriggers.composeProactiveQuestion(List.of(), "小派蒙", "打招呼", 60).length() <= 60);
    }

    @Test void rejectsBlankInput() {
        assertFalse(AiChatTriggers.triggers(null, "小派蒙"));
        assertFalse(AiChatTriggers.triggers("", "小派蒙"));
        assertFalse(AiChatTriggers.triggers("   ", "小派蒙"));
        assertFalse(AiChatTriggers.triggers("小派蒙在吗", null));
        assertFalse(AiChatTriggers.triggers("小派蒙在吗", ""));
    }

    @Test void questionIncludesContextSpeakerAndName() {
        String question = AiChatTriggers.composeQuestion(
                List.of("Alice: 大家好", "Bob: 挖到钻石了"),
                "Carol", "小派蒙 在吗",
                "小派蒙", 0);
        assertTrue(question.contains("小派蒙"));
        assertTrue(question.contains("Alice: 大家好"));
        assertTrue(question.contains("Bob: 挖到钻石了"));
        assertTrue(question.contains("Carol 说: 小派蒙 在吗"));
    }

    @Test void questionDropsOldestContextWhenTooLong() {
        List<String> recent = List.of(
                "AAAAAAAAAA: 1111111111", "BBBBBBBBBB: 2222222222", "CCCCCCCCCC: 3333333333");
        String question = AiChatTriggers.composeQuestion(recent, "Carol", "小派蒙 在吗", "小派蒙", 120);
        assertTrue(question.length() <= 120);
        // 最旧的上下文先被丢掉，本次点名消息始终保留
        assertFalse(question.contains("AAAAAAAAAA"));
        assertTrue(question.contains("Carol 说: 小派蒙 在吗"));
    }

    @Test void questionWithoutContextStillFitsLimit() {
        String question = AiChatTriggers.composeQuestion(List.of(), "Carol",
                "小派蒙".repeat(50), "小派蒙", 100);
        assertTrue(question.length() <= 100);
    }

    @Test void unlimitedWhenMaxLengthNotPositive() {
        String question = AiChatTriggers.composeQuestion(List.of("a".repeat(200)), "Bob",
                "hi", "小派蒙", 0);
        assertTrue(question.contains("a".repeat(200)));
        assertTrue(question.length() > 200);
    }
}
