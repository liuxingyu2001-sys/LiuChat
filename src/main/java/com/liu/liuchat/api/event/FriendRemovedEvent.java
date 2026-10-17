package com.liu.liuchat.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** 好友关系移除后触发（不能取消）。 */
public final class FriendRemovedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String playerName;
    private final UUID friendId;
    private final String friendName;

    public FriendRemovedEvent(UUID playerId, String playerName, UUID friendId, String friendName) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.friendId = friendId;
        this.friendName = friendName;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getFriendId() { return friendId; }
    public String getFriendName() { return friendName; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
