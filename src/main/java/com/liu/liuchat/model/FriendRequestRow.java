package com.liu.liuchat.model;

/** 一条待处理的好友请求；sender → target 单向。 */
public record FriendRequestRow(String senderUuid, String senderName, String targetUuid, String targetName,
                               long createdAt, long expiresAt) {
    public boolean expired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}
