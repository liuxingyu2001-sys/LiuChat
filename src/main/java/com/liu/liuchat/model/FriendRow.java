package com.liu.liuchat.model;

/** 双向好友表中的一行（已按 UUID 归一化为“对方”视角）。 */
public record FriendRow(String uuid, String name, long since) {
}
