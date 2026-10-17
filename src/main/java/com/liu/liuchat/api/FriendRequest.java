package com.liu.liuchat.api;

import java.util.UUID;

/** 一条单向好友请求。 */
public record FriendRequest(UUID senderId, String senderName, UUID targetId, String targetName,
                            long createdAt, long expiresAt) {
}
