package com.liu.liuchat.api;

import java.util.UUID;

/**
 * 一名好友的快照。
 *
 * <p>{@code online} 为查询时的在线状态（本服或跨服名单），跨服状态最长约 60 秒延迟。
 * {@code lastSeen} 为最近一次登录/退出时间（epoch 毫秒，0 = 没有记录，例如从未在本组服登录过）；
 * 在线好友的该值是本次登录时间，离线好友用于界面显示“最近在线”。
 */
public record Friend(UUID uuid, String name, long since, boolean online, long lastSeen) {

    /** 兼容旧调用：不携带最近在线时间。 */
    public Friend(UUID uuid, String name, long since, boolean online) {
        this(uuid, name, since, online, 0L);
    }
}
