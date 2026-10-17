package com.liu.liuchat.model;

/** 玩家名 → UUID 目录，用于离线好友/邮件按名字查找。 */
public record PlayerIdentity(String uuid, String name, long lastSeen) {
}
