package com.liu.liuchat.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** 好友请求写入前触发；取消后不会创建请求。 */
public final class FriendRequestEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID senderId;
    private final String senderName;
    private final UUID targetId;
    private final String targetName;
    private long expiresAt;
    private boolean cancelled;

    public FriendRequestEvent(UUID senderId, String senderName, UUID targetId, String targetName, long expiresAt) {
        this.senderId = senderId;
        this.senderName = senderName;
        this.targetId = targetId;
        this.targetName = targetName;
        this.expiresAt = expiresAt;
    }

    public UUID getSenderId() { return senderId; }
    public String getSenderName() { return senderName; }
    public UUID getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
