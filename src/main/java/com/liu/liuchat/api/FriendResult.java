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
    STORAGE_UNAVAILABLE
}
