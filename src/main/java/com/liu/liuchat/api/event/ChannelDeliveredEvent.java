package com.liu.liuchat.api.event;

import com.liu.liuchat.api.ChatChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Local non-GLOBAL message accepted by moderation and submitted for local delivery.
 * Fired on the sender's owning thread. Observers may relay rendered text; do not resend locally.
 */
public final class ChannelDeliveredEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ChatChannel channel;
    private final Component rendered;
    public ChannelDeliveredEvent(Player player, ChatChannel channel, Component rendered) {
        this.player = player;
        this.channel = channel;
        this.rendered = rendered;
    }
    public Player getPlayer() { return player; }
    public ChatChannel getChannel() { return channel; }
    public Component getRendered() { return rendered; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
