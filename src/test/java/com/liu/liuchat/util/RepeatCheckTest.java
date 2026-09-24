package com.liu.liuchat.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatCheckTest {

    private final UUID id = UUID.randomUUID();

    /** 默认配置：窗口 30 秒、相似度 95%、最短比较长度 5 */
    private boolean spam(RepeatCheck check, String message, long now, boolean itemShow) {
        return check.spam(id, message, now, 30, 95, 5, itemShow);
    }

    @Test void blocksIdenticalMessageInsideWindow() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hello", 0L, false));
        assertTrue(spam(check, "hello", 1_000L, false));
    }

    @Test void windowZeroDisablesCheck() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(check.spam(id, "hello", 0L, 0, 95, 5, false));
        assertFalse(check.spam(id, "hello", 1_000L, 0, 95, 5, false));
    }

    @Test void expiredWindowDoesNotCompare() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hello", 0L, false));
        assertFalse(spam(check, "hello", 31_000L, false));
    }

    @Test void identicalShortMessageIgnoresMinLength() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hi", 0L, false));
        assertTrue(spam(check, "hi", 1_000L, false));
    }

    @Test void shortMessageOnlyBlockedWhenIdentical() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hi", 0L, false));
        assertFalse(spam(check, "ho", 1_000L, false));
    }

    @Test void similarityDecidesForLongMessages() {
        RepeatCheck lenient = new RepeatCheck();
        assertFalse(spam(lenient, "abcdefghij", 0L, false));
        // 与上一条相似度 90%：阈值 80 拦、阈值 95 放
        assertTrue(lenient.spam(id, "abcdefghiX", 1_000L, 30, 80, 5, false));

        RepeatCheck strict = new RepeatCheck();
        assertFalse(spam(strict, "abcdefghij", 0L, false));
        assertFalse(strict.spam(id, "abcdefghiX", 1_000L, 30, 95, 5, false));
    }

    @Test void minLengthGuardsSimilarityComparison() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "abcdefghij", 0L, false));
        // 相似度 90% 本可拦截，但当前消息长度 10 < 11 不参与相似度比较
        assertFalse(check.spam(id, "abcdefghiX", 1_000L, 30, 80, 11, false));
    }

    @Test void itemShowcaseIsSkippedAndNotRecorded() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hello", 0L, false));
        // [i] 物品展示不拦截，也不刷新「上次发言」
        assertFalse(spam(check, "[i] hello", 1_000L, true));
        assertFalse(spam(check, "[i] hello", 2_000L, true));
        // 仍与 t=0 的 hello 比较
        assertTrue(spam(check, "hello", 3_000L, false));
    }

    @Test void blockedMessageDoesNotRefreshRecord() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hello", 0L, false));
        assertTrue(spam(check, "hello", 1_000L, false));
        assertTrue(spam(check, "hello", 2_000L, false));
        // 超出窗口后放行
        assertFalse(spam(check, "hello", 31_000L, false));
    }

    @Test void clearForgetsPreviousMessage() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hello", 0L, false));
        check.clear(id);
        assertFalse(spam(check, "hello", 1_000L, false));
    }

    @Test void blankAndNullMessagesPass() {
        RepeatCheck check = new RepeatCheck();
        assertFalse(spam(check, "hello", 0L, false));
        assertFalse(spam(check, "", 1_000L, false));
        assertFalse(spam(check, "   ", 2_000L, false));
        assertFalse(spam(check, null, 3_000L, false));
    }
}
