package com.liu.liuchat.api.event;

import com.liu.liuchat.api.ChatChannel;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.Objects;

/** Fired on sender's owning thread after permission checks, before moderation. Replacement text is moderated too. */
public final class ChannelMessageEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ChatChannel channel;
    private String message;
    private boolean cancelled;
    public ChannelMessageEvent(Player player, ChatChannel channel, String message) {
        this.player = player; this.channel = channel; this.message = message;
    }
    public Player getPlayer() { return player; }
    public ChatChannel getChannel() { return channel; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = Objects.requireNonNull(message); }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
