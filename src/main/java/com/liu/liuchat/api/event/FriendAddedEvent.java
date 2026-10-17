package com.liu.liuchat.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** 双向好友关系建立后触发（不能取消）。 */
public final class FriendAddedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String playerName;
    private final UUID friendId;
    private final String friendName;
    private final long since;

    public FriendAddedEvent(UUID playerId, String playerName, UUID friendId, String friendName, long since) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.friendId = friendId;
        this.friendName = friendName;
        this.since = since;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public UUID getFriendId() { return friendId; }
    public String getFriendName() { return friendName; }
    public long getSince() { return since; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
