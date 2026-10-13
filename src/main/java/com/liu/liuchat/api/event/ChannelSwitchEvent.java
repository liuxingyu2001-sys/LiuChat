package com.liu.liuchat.api.event;

import com.liu.liuchat.api.ChatChannel;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Cancellable before-selection event, fired on the player's owning thread. */
public final class ChannelSwitchEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ChatChannel previous, next;
    private boolean cancelled;
    public ChannelSwitchEvent(Player player, ChatChannel previous, ChatChannel next) {
        this.player = player; this.previous = previous; this.next = next;
    }
    public Player getPlayer() { return player; }
    public ChatChannel getPrevious() { return previous; }
    public ChatChannel getNext() { return next; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
