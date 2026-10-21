package com.liu.liuchat.service;

import com.liu.liuchat.api.FriendResult;

/**
 * 好友操作结果到消息键的唯一映射：界面静默失败、指令反馈都走这里，
 * 避免新增结果码时各处开关漏改。
 */
public final class FriendResultMessages {
    private FriendResultMessages() {
    }

    /**
     * @param okKey 结果为 {@link FriendResult#OK} 时使用的成功消息键
     */
    public static String key(FriendResult result, String okKey) {
        return switch (result) {
            case OK -> okKey;
            case ACCEPTED_MUTUAL -> "friend.mutual-accepted";
            case REQUEST_SENT -> "friend.request-sent";
            case REQUEST_EXISTS -> "friend.request-exists";
            case ALREADY_FRIENDS -> "friend.already-friends";
            case NOT_FRIENDS -> "friend.not-friends";
            case REQUEST_NOT_FOUND -> "friend.request-not-found";
            case FRIENDS_FULL -> "friend.full";
            case REQUEST_LIMIT -> "friend.request-limit";
            case SELF -> "friend.self";
            case CANCELLED -> "friend.cancelled";
            case NOT_FOUND -> "friend.not-found";
            case DISABLED -> "friend.disabled";
            case TELEPORT_DISABLED -> "friend.teleport-disabled";
            case TELEPORT_DENIED -> "friend.teleport-no-permission";
            case TELEPORT_OFFLINE -> "friend.teleport-offline";
            case TELEPORT_OTHER_SERVER -> "friend.teleport-other-server";
            case TELEPORT_COOLDOWN -> "friend.teleport-cooldown";
            case TELEPORT_FAILED -> "friend.teleport-failed";
            case TELEPORT_CROSS_SERVER -> "friend.teleport-queued";
            default -> "friend.storage-unavailable";
        };
    }
}
