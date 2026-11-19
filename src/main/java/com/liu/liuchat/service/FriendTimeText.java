package com.liu.liuchat.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 好友列表「最近在线」时间的展示格式：今年的记录只显示 {@code MM-dd HH:mm}，
 * 跨年记录带上年份，避免把一年前的记录误读成今年。
 */
final class FriendTimeText {
    private static final DateTimeFormatter CURRENT_YEAR = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter OTHER_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private FriendTimeText() {
    }

    /** @param lastSeenMillis 最近在线时间（epoch 毫秒）；@param nowMillis 当前时间，用于判断是否跨年 */
    static String format(long lastSeenMillis, long nowMillis, ZoneId zone) {
        ZonedDateTime seen = Instant.ofEpochMilli(lastSeenMillis).atZone(zone);
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        return (seen.getYear() == now.getYear() ? CURRENT_YEAR : OTHER_YEAR).format(seen);
    }
}
