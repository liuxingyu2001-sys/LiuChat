package com.liu.liuchat.api;

/** 好友操作结果。 */
public enum FriendResult {
    OK,
    /** 对方也正好请求了你，本次自动成为好友。 */
    ACCEPTED_MUTUAL,
    SELF,
    ALREADY_FRIENDS,
    NOT_FRIENDS,
    REQUEST_SENT,
    REQUEST_EXISTS,
    REQUEST_NOT_FOUND,
    FRIENDS_FULL,
    REQUEST_LIMIT,
    REQUESTS_DISABLED,
    CANCELLED,
    NOT_FOUND,
    DISABLED,
    STORAGE_UNAVAILABLE,
    /** 好友传送已在配置中关闭。 */
    TELEPORT_DISABLED,
    /** 没有好友传送权限。 */
    TELEPORT_DENIED,
    /** 好友不在线。 */
    TELEPORT_OFFLINE,
    /** 好友在其它子服，无法直接传送。 */
    TELEPORT_OTHER_SERVER,
    /** 传送冷却中。 */
    TELEPORT_COOLDOWN,
    /** 传送被服务端拒绝或取消。 */
    TELEPORT_FAILED
}
