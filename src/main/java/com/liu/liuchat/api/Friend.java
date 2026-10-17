package com.liu.liuchat.api;

import java.util.UUID;

/**
 * 一名好友的快照。{@code online} 为查询时的在线状态（本服或跨服名单），跨服状态最长约 60 秒延迟。
 */
public record Friend(UUID uuid, String name, long since, boolean online) {
}
