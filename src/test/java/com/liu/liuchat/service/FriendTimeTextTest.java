package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 好友“最近在线”时间格式：同年省略年份、跨年带年份。 */
class FriendTimeTextTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static long millis(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    @Test
    void currentYearOmitsYear() {
        long now = millis(2026, 11, 19, 23, 0);
        assertEquals("11-19 22:45", FriendTimeText.format(millis(2026, 11, 19, 22, 45), now, ZONE));
    }

    @Test
    void otherYearIncludesYear() {
        long now = millis(2026, 1, 2, 8, 0);
        assertEquals("2025-12-31 23:59", FriendTimeText.format(millis(2025, 12, 31, 23, 59), now, ZONE));
    }
}
