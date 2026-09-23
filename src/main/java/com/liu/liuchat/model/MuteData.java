package com.liu.liuchat.model;

/**
 * 一条禁言记录。
 *
 * @param uuid    目标玩家 uuid
 * @param name    目标玩家名（展示与按名兜底匹配用）
 * @param expireAt 过期时间戳（epoch 毫秒），{@link #PERMANENT} 表示永久
 * @param reason  禁言原因
 * @param operator操作者名
 */
public record MuteData(String uuid, String name, long expireAt, String reason, String operator) {

    public static final long PERMANENT = -1L;

    public boolean isPermanent() {
        return expireAt <= 0;
    }

    public boolean isExpired(long now) {
        return !isPermanent() && now >= expireAt;
    }
}
